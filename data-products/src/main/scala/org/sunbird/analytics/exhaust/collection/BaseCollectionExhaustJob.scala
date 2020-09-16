package org.sunbird.analytics.exhaust.collection

import scala.collection.mutable

import org.apache.spark.SparkContext
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.StructType
import org.ekstep.analytics.framework.FrameworkContext
import org.ekstep.analytics.framework.IJob
import org.ekstep.analytics.framework.JobConfig
import org.ekstep.analytics.framework.conf.AppConf
import org.ekstep.analytics.framework.util.JSONUtils
import org.ekstep.analytics.framework.util.JobLogger
import org.sunbird.analytics.exhaust.BaseReportsJob
import org.sunbird.analytics.exhaust.OnDemandExhaustJob
import org.sunbird.analytics.util.DecryptUtil
import org.sunbird.analytics.exhaust.JobRequest
import org.ekstep.analytics.framework.util.CommonUtil
import org.ekstep.analytics.framework.util.DatasetUtil.extensions
import org.joda.time.format.DateTimeFormatter
import org.joda.time.format.DateTimeFormat
import org.joda.time.DateTimeZone
import org.ekstep.analytics.framework.Level.{ERROR, INFO}
import org.ekstep.analytics.util.Constants
import org.ekstep.analytics.framework.util.RestUtil

case class UserData(userid: String, state: Option[String] = Option(""), district: Option[String] = Option(""), userchannel: Option[String] = Option(""), orgname: Option[String] = Option(""),
                    firstname: Option[String] = Option(""), lastname: Option[String] = Option(""), maskedemail: Option[String] = Option(""), maskedphone: Option[String] = Option(""),
                    block: Option[String] = Option(""), externalid: Option[String] = Option(""), schoolname: Option[String] = Option(""), schooludisecode: Option[String] = Option(""))

case class CollectionConfig(batchId: Option[String], searchFilter: Option[Map[String, AnyRef]])
case class CollectionBatch(batchId: String, collectionId: String, batchName: String, custodianOrgId: String, requestedOrgId: String, collectionOrgId: String, collectionName: String, userConsent: String)
case class CollectionBatchResponse(batchId: String, file: String, status: String, statusMsg: String)
case class CollectionDetails(result: Result)
case class Result(content: List[CollectionInfo])
case class CollectionInfo(channel: String, identifier: String, name: String)

trait BaseCollectionExhaustJob extends BaseReportsJob with IJob with OnDemandExhaustJob {

  implicit val className: String = getClassName;
  private val userCacheDBSettings = Map("table" -> "user", "infer.schema" -> "true", "key.column" -> "userid");
  private val userDBSettings = Map("table" -> "user", "keyspace" -> AppConf.getConfig("sunbird.user.keyspace"));
  private val userConsentDBSettings = Map("table" -> "user_consent", "keyspace" -> AppConf.getConfig("sunbird.user.keyspace"));
  private val userEnrolmentDBSettings = Map("table" -> "user_enrolments", "keyspace" -> AppConf.getConfig("sunbird.courses.keyspace"));
  private val collectionBatchDBSettings = Map("table" -> "course_batch", "keyspace" -> AppConf.getConfig("sunbird.courses.keyspace"));
  private val redisFormat = "org.apache.spark.sql.redis";
  private val cassandraFormat = "org.apache.spark.sql.cassandra";
  private val maskedFields = Array("email", "phonenumber");

  val metrics: mutable.Map[String, BigInt] = mutable.Map[String, BigInt]()

  /** START - Job Execution Methods */
  def main(config: String)(implicit sc: Option[SparkContext] = None, fc: Option[FrameworkContext] = None) {

    JobLogger.init(jobName)
    JobLogger.start(s"$jobName started executing", Option(Map("config" -> config, "model" -> jobName)))

    implicit val jobConfig = JSONUtils.deserialize[JobConfig](config)
    implicit val spark: SparkSession = openSparkSession(jobConfig)
    implicit val frameworkContext: FrameworkContext = getReportingFrameworkContext()
    logTime(execute(), "Job execution complete");
  }

  def execute()(implicit spark: SparkSession, fc: FrameworkContext, config: JobConfig) {
    val modelParams = config.modelParams.getOrElse(Map[String, Option[AnyRef]]());
    val mode = modelParams.getOrElse("mode", "OnDemand").asInstanceOf[String];

    val custodianOrgId = getCustodianOrgId();
    val userCachedDF = getUserCacheDF(getUserCacheColumns(), true);
    mode.toLowerCase() match {
      case "standalone" =>
        executeStandAlone(custodianOrgId, userCachedDF)
      case _ =>
        executeOnDemand(custodianOrgId, userCachedDF);
    }
  }

  def executeStandAlone(custodianOrgId: String, userCachedDF: DataFrame)(implicit spark: SparkSession, fc: FrameworkContext, config: JobConfig) {
    val modelParams = config.modelParams.getOrElse(Map[String, Option[AnyRef]]());
    val batchId = modelParams.get("batchId").asInstanceOf[Option[String]];
    val batchFilter = modelParams.get("batchFilter").asInstanceOf[Option[List[String]]];
    val searchFilter = modelParams.get("batchFilter").asInstanceOf[Option[Map[String, AnyRef]]];
    val collectionBatches = getCollectionBatches(batchId, batchFilter, searchFilter, custodianOrgId, "System");
    val result = processBatches(userCachedDF, collectionBatches);
    // TODO: Log result
  }

  def executeOnDemand(custodianOrgId: String, userCachedDF: DataFrame)(implicit spark: SparkSession, fc: FrameworkContext, config: JobConfig) {

    val modelParams = config.modelParams.getOrElse(Map[String, Option[AnyRef]]());
    val container = modelParams.getOrElse("storageContainer", "reports").asInstanceOf[String]
    val storageConfig = getStorageConfig(container, "");
    val requests = getRequests(jobId());
    val result = for (request <- requests) yield {
      if (validateRequest(request)) {
        processRequest(request, custodianOrgId, userCachedDF)
      } else {
        markRequestAsFailed(request, "Invalid request")
      }
    }
    saveRequests(storageConfig, result);
  }

  def processRequest(request: JobRequest, custodianOrgId: String, userCachedDF: DataFrame)(implicit spark: SparkSession, fc: FrameworkContext, config: JobConfig): JobRequest = {
    val collectionConfig = JSONUtils.deserialize[CollectionConfig](request.request_data);
    val collectionBatches = getCollectionBatches(collectionConfig.batchId, None, collectionConfig.searchFilter, custodianOrgId, request.requested_channel)
    val result = CommonUtil.time(processBatches(userCachedDF, collectionBatches));
    val response = result._2;
    val failedBatches = response.filter(p => p.status.equals("FAILED"));
    if (failedBatches.size > 0 || response.size == 0) {
      markRequestAsFailed(request, failedBatches.map(f => f.statusMsg).mkString(","))
    } else {
      request.status = "SUCCESS";
      request.download_urls = Option(response.map(f => f.file));
      request.execution_time = Option(result._1);
      request
    }
  }

  def validateRequest(request: JobRequest): Boolean = {
    val collectionConfig = JSONUtils.deserialize[CollectionConfig](request.request_data);
    if (collectionConfig.batchId.isEmpty && collectionConfig.searchFilter.isEmpty) false else true
  }

  def markRequestAsFailed(request: JobRequest, failedMsg: String): JobRequest = {
    request.status = "FAILED";
    request.dt_job_completed = Option(System.currentTimeMillis());
    request.iteration = Option(request.iteration.getOrElse(0) + 1);
    request.err_message = Option(failedMsg);
    request
  }

  def getCollectionBatches(batchId: Option[String], batchFilter: Option[List[String]], searchFilter: Option[Map[String, AnyRef]], custodianOrgId: String, requestedOrgId: String)(implicit spark: SparkSession, fc: FrameworkContext, config: JobConfig): List[CollectionBatch] = {
    
    val encoder = Encoders.product[CollectionBatch];
    val collectionBatches = getCollectionBatchDF(false);
    if (batchId.isDefined || batchFilter.isDefined) {
      val batches = if(batchId.isDefined) collectionBatches.filter(col("batchid") === batchId.get) else collectionBatches.filter(col("batchid").isin(batchFilter.get:_*))
      val collectionIds = batches.select("courseid").dropDuplicates().collect().map(f => f.get(0));
      val collectionDF = getCollections(Map("request" -> Map("filters" -> Map("identifier" -> collectionIds))));
      val joinedDF = batches.join(collectionDF, batches("courseid") === collectionDF("identifier"), "inner");
      val finalDF = joinedDF.withColumn("custodianOrgId", lit(custodianOrgId))
        .withColumn("requestedOrgId", when(lit(requestedOrgId) === "System", col("channel")).otherwise(requestedOrgId))
        .select(col("batchid").as("batchId"),col("courseid").as("collectionId"),col("name").as("batchName"),col("custodianOrgId"),col("requestedOrgId"),col("channel").as("collectionOrgId"),col("collectionName"),col("userConsent"));
      finalDF.as[CollectionBatch](encoder).collect().toList
    } else if(searchFilter.isDefined) {
      val collectionDF = getCollections(searchFilter.get)
      val joinedDF = collectionBatches.join(collectionDF, collectionBatches("courseid") === collectionDF("identifier"), "inner");
      val finalDF = joinedDF.withColumn("custodianOrgId", lit(custodianOrgId))
        .withColumn("requestedOrgId", when(lit(requestedOrgId) === "System", col("channel")).otherwise(requestedOrgId))
        .select(col("batchid").as("batchId"),col("courseid").as("collectionId"),col("name").as("batchName"),col("custodianOrgId"),col("requestedOrgId"),col("channel").as("collectionOrgId"),col("collectionName"),col("userConsent"));
      finalDF.as[CollectionBatch](encoder).collect().toList
    } else {
      List();
    }
  }

  def processBatches(userCachedDF: DataFrame, collectionBatches: List[CollectionBatch])(implicit spark: SparkSession, fc: FrameworkContext, config: JobConfig): List[CollectionBatchResponse] = {
    
    val modelParams = config.modelParams.getOrElse(Map[String, Option[AnyRef]]());
    val container = modelParams.getOrElse("storageContainer", "reports").asInstanceOf[String]
    for (batch <- filterCollectionBatches(collectionBatches)) yield {
      val userEnrolmentDF = getUserEnrolmentDF(false).join(userCachedDF, Seq("userid"), "inner");
      val filteredDF = filterUsers(batch, userEnrolmentDF);
      val reportDF = processBatch(filteredDF, batch);
      val storageConfig = getStorageConfig(container, AppConf.getConfig("collection.exhaust.store.prefix"))
      val files = reportDF.saveToBlobStore(storageConfig, "csv", getFilePath(batch.batchId), Option(Map("header" -> "true")), None);
      CollectionBatchResponse(batch.batchId, files.head, "SUCCESS", "");
    }
  }
  
  /** END - Job Execution Methods */

  /** START - Overridable Methods */
  def processBatch(userEnrolmentDF: DataFrame, collectionBatch: CollectionBatch)(implicit spark: SparkSession, fc: FrameworkContext, config: JobConfig): DataFrame;
  def jobId(): String;
  def jobName(): String;
  def getClassName(): String;
  def getReportPath(): String;
  def getReportKey(): String;
  def filterCollectionBatches(collectionBatches: List[CollectionBatch]): List[CollectionBatch] = {
    collectionBatches
  }

  def getUserCacheColumns(): Seq[String] = {
    Seq("userid", "username", "state", "district")
  }
  /** END - Overridable Methods */
  
  /** START - Utility Methods */
  def toDecryptFun(str: String): Option[String] = {
    Some(DecryptUtil.decryptData(str))
  }

  val toDecrypt = udf[Option[String], String](toDecryptFun)
  
  def getFilePath(batchId: String)(implicit config: JobConfig): String = {
    getReportPath() + batchId + "_" + getReportKey() + "_" + getDate()
  }
  
  def getDate(): String = {
    val dateFormat: DateTimeFormatter = DateTimeFormat.forPattern("ddMMyyyy").withZone(DateTimeZone.forOffsetHoursMinutes(5, 30));
    dateFormat.print(System.currentTimeMillis());
  }

  def getCustodianOrgId()(implicit spark: SparkSession): String = {
    loadData(Map("table" -> "system_settings", "keyspace" -> AppConf.getConfig("sunbird.user.keyspace")), cassandraFormat, new StructType())
      .where(col("id") === "custodianOrgId" && col("field") === "custodianOrgId").select(col("value")).select("value").first().getString(0)
  }
  
  def getUserEnrolmentDF(persist: Boolean)(implicit spark: SparkSession): DataFrame = {
    val df = loadData(userEnrolmentDBSettings, cassandraFormat, new StructType()).select("batchid", "userid", "courseid", "active", "certificates", "enrolleddate", "completedon")
    if (persist) df.persist() else df
  }
  
  def getCollections(searchFilter: Map[String, AnyRef])(implicit spark: SparkSession, fc: FrameworkContext, config: JobConfig): DataFrame = {
    val apiURL = Constants.COMPOSITE_SEARCH_URL
    val request = JSONUtils.serialize(searchFilter)
    val response = RestUtil.post[CollectionDetails](apiURL, request).result.content
    spark.createDataFrame(response).withColumnRenamed("name", "collectionName").select("channel", "identifier", "collectionName", "userConsent")
  }
  
  def getCollectionBatchDF(persist: Boolean)(implicit spark: SparkSession): DataFrame = {
    val df = loadData(collectionBatchDBSettings, cassandraFormat, new StructType()).select("courseid", "batchid", "enddate", "startdate", "name")
    if (persist) df.persist() else df
  }

  def getUserCacheDF(cols: Seq[String], persist: Boolean)(implicit spark: SparkSession): DataFrame = {
    val schema = Encoders.product[UserData].schema
    val df = loadData(userCacheDBSettings, redisFormat, schema).withColumn("username", concat_ws(" ", col("firstname"), col("lastname"))).select(cols.head, cols.tail: _*);
    if (persist) df.persist() else df
  }

  def getUserDF(cols: Seq[String], persist: Boolean)(implicit spark: SparkSession): DataFrame = {
    val df = loadData(userDBSettings, cassandraFormat, new StructType()).filter(col("userid").isNotNull).select(cols.head, cols.tail: _*);
    if (persist) df.persist() else df
  }

  def filterUsers(collectionBatch: CollectionBatch, reportDF: DataFrame)(implicit spark: SparkSession): DataFrame = {

    if (collectionBatch.requestedOrgId.equals(collectionBatch.collectionOrgId)) {
      reportDF
    } else {
      reportDF.where(col("rootOrgId") === collectionBatch.requestedOrgId);
    }
  }

  def getUserConsentDF(collectionBatch: CollectionBatch)(implicit spark: SparkSession): DataFrame = {
    val df = loadData(userConsentDBSettings, cassandraFormat, new StructType());
    df.where(col("object_id") === collectionBatch.collectionId && col("subject_id") === collectionBatch.requestedOrgId).dropDuplicates("userid", "object_id", "subject_id").select("userid", "object_id", "subject_id", "consented", "consented_date");
  }

  def applyConsentRules(collectionBatch: CollectionBatch, reportDF: DataFrame)(implicit spark: SparkSession): DataFrame = {

    if (collectionBatch.requestedOrgId.equals(collectionBatch.custodianOrgId)) {
      reportDF.withColumn("consentFlag", lit("false"));
    } else {
      val consentDF = getUserConsentDF(collectionBatch);
      val resultDF = reportDF.join(consentDF, Seq("userid"), "left_outer").withColumnRenamed("consented", "consentFlag").drop("object_id", "subject_id")
      // Global consent - will be updated in 3.4 to read from user_consent table
      resultDF.withColumn("consentFlag", when(col("rootOrgId") === collectionBatch.requestedOrgId, "true").when(col("consentFlag").isNotNull, col("consentFlag")).otherwise("false"))
    }

  }

  def decryptMaskedInfo(userDF: DataFrame)(implicit spark: SparkSession): DataFrame = {

    val schema = userDF.schema
    val unmaskFields = schema.fields.filter(field => maskedFields.contains(field.name));
    val resultDF = unmaskFields.foldLeft(userDF)((df, field) => df.withColumn(field.name, when(col("consentFlag") === "true", toDecrypt(col(field.name))).otherwise(col(field.name))))
    resultDF
  }
  
  def logTime[R](block: => R, message: String): R = {
    val res = CommonUtil.time(block);
    JobLogger.log(message, Some(Map("timeTaken" -> res._1)), INFO)
    res._2
  }
  /** END - Utility Methods */

}