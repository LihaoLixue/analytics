package com.dtc.analytic.scala.works

import java.text.SimpleDateFormat
import java.util.{Date, Properties}

import com.dtc.analytic.scala.common.{CountUtils, DtcConf, LevelEnum, Utils}
import com.dtc.analytic.scala.dtcexpection.DtcException
import org.apache.flink.api.common.functions.{MapFunction, RuntimeContext}
import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.connectors.elasticsearch.{ElasticsearchSinkFunction, RequestIndexer}
import org.apache.flink.streaming.connectors.elasticsearch6.ElasticsearchSink
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer09
import org.apache.http.HttpHost
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.client.Requests
import org.elasticsearch.common.xcontent.XContentType
import org.slf4j.{Logger, LoggerFactory}

/**
  * Created on 2019-05-27
  *
  * @author :hao.li
  */
object StreamingFlinkScala {
  def logger: Logger = LoggerFactory.getLogger(StreamingFlinkScala.getClass)

  def main(args: Array[String]): Unit = {
    DtcConf.setup()
    val env: StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment
    //    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    env.setParallelism(1)
    val conf = DtcConf.getConf()
    val level = conf.get("log.level")
    val lev: Int = LevelEnum.getIndex(level)
    //    env.registerCachedFile("hdfs://10.3.6.7:9000/user/dtc/flink-connector-elasticsearch-base_2.11-1.6.4.jar", "hdfsFile")
    val brokerList = conf.get("flink.kafka.broker.list")
    val topic = conf.get("flink.kafka.topic")
    val groupId = conf.get("flink.kafka.groupid")


    val prop = new Properties()
    prop.setProperty("bootstrap.servers", brokerList)
    prop.setProperty("group.id", groupId)
    prop.setProperty("topic", topic)
    val myConsumer = new FlinkKafkaConsumer09[String](topic, new SimpleStringSchema(), prop)
    //    val waterMarkStream = myConsumer.assignTimestampsAndWatermarks(new AssignerWithPeriodicWatermarks[(String)] {
    //      var currentMaxTimestamp = 0L
    //      var maxOutOfOrderness = 10000L // 最大允许的乱序时间是10s
    //      val sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    //
    //      override def extractTimestamp(element: (String), previousElementTimestamp: Long) = {
    //        val event = element.split("\\$\\$")
    //        var time1 = event(0).replace("T", " ").split("\\+")(0).replace("-", "/")
    //        var time2 = new Date(time1).getTime
    //        currentMaxTimestamp = Math.max(time2, currentMaxTimestamp)
    //        time2
    //      }
    //
    //      override def getCurrentWatermark = new Watermark(currentMaxTimestamp - maxOutOfOrderness)
    //
    //    })
    val text = env.addSource(myConsumer)

    val inputMap = text.map(new MyMapFunction).filter(!_.contains("null"))
    inputMap.print()
    val httpHosts = new java.util.ArrayList[HttpHost]
    val es_host = conf.get("dtc.es.nodes")
    if (Utils.isEmpty(es_host)) {
      throw new DtcException("Es_Host is null!")
    }
    val host_es = es_host.split(",")
    for (x <- host_es) {
      var ip = x.split(":")(0)
      var host = x.split(":")(1)
      httpHosts.add(new HttpHost(ip, host.toInt, "http"))
    }

    val es_index = conf.get("dtc.es.flink.index.name")

    val es_type = conf.get("dtc.es.flink.type.name")
    if (Utils.isEmpty(es_index) || Utils.isEmpty(es_type)) {
      throw new DtcException("Es_index or type is null!")
    }
    var esSink = new ElasticsearchSink.Builder[String](httpHosts, new ElasticsearchSinkFunction[String] {
      def createIndexRequest(element: String): IndexRequest = {
        return Requests.indexRequest()
          .index(es_index)
          .`type`(es_type)
          .source(element, XContentType.JSON)
      }

      override def process(element: String, ctx: RuntimeContext, indexer: RequestIndexer): Unit = {
        //          val indexRequest: IndexRequest = Requests.indexRequest().index(indexName).`type`(indexType).source(element,XContentType.JSON)
        //                  indexer.add(indexRequest)
        indexer.add(createIndexRequest(element))
      }
    }
    )

    esSink.setBulkFlushMaxActions(1)
    inputMap.addSink(esSink.build())
    env.execute("StreamingWindowWatermarkScala")
  }


  def tranTimeToString(timestamp: String): String = {
    val fm = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    val time = fm.format(new Date(timestamp.toLong))
    time
  }


  //    def getEsSink(indexName: String,indexType:String): ElasticsearchSink[String] = {
  //      //new接口---> 要实现一个方法
  //      val esSinkFunc: ElasticsearchSinkFunction[String] = new ElasticsearchSinkFunction[String] {
  //        override def process(element: String, ctx: RuntimeContext, indexer: RequestIndexer): Unit = {
  //          val indexRequest: IndexRequest = Requests.indexRequest().index(indexName).`type`(indexType).source(element)
  //          indexer.add(indexRequest)
  //        }
  //      }
  //      val esSinkBuilder = new ElasticsearchSink.Builder[String](hostList, esSinkFunc)
  //      esSinkBuilder.setBulkFlushMaxActions(10)
  //      val esSink: ElasticsearchSink[String] = esSinkBuilder.build()
  //      esSink
  //    }


}

class MyMapFunction extends MapFunction[String, String] {
  override def map(line: String): String = {
    var message = ""
    if (line.contains("$DTC$")) {
      val splitDtc: Array[String] = line.split("\\$DTC\\$")
      val event = splitDtc(0).split("\\$\\$")
      message += "{" + "\"time\"" + ":" + "\"" + event(0).trim + "\"" + "," + "\"device\"" + ":" + "\"" + event(1).trim +
        "\"" + "," + "\"" + "level" + "\"" + ":" + "\"" + event(2).trim + "\"" + "," + "\"" + "hostname" + "\"" + ":" +
        "\"" + event(3).trim + "\"" + "," + "\"" + "message" + "\"" + ":" + "\"" + event(4).trim + "\""
      var str = ""
      for (i <- 1 until splitDtc.length) {
        str = splitDtc(i).trim + "\n"
      }
      message += "\"" + "cause" + "\"" + ":" + "\"" + str.trim + "\"" + "}"
      var time1 = event(0).replace("T", " ").split("\\+")(0).replace("-", "/")
      var time2 = new Date(time1).getTime
      CountUtils.incrementEventRightCount
      return message
    } else if (line.contains("$$")) {
      val event = line.split("\\$\\$")
      message += "{" + "\"time\"" + ":" + "\"" + event(0).trim + "\"" + "," + "\"device\"" + ":" + "\"" + event(1).trim +
        "\"" + "," + "\"" + "level" + "\"" + ":" + "\"" + event(2).trim + "\"" + "," + "\"" + "hostname" + "\"" + ":" +
        "\"" + event(3).trim + "\"" + "," + "\"" + "message" + "\"" + ":" + "\"" + event(4).trim + "\"" + "}"
      var time1 = event(0).replace("T", " ").split("\\+")(0).replace("-", "/")
      var time2 = new Date(time1).getTime
      CountUtils.incrementEventRightCount
      return message
    } else {
      message += "{" + "null" + "}"
      CountUtils.incrementEventErrorCount
      return message
    }

  }
}

//  def getEsSink(indexName: String): ElasticsearchSink[String] = {
//    //new接口---> 要实现一个方法
//    val esSinkFunc: ElasticsearchSinkFunction[String] = new ElasticsearchSinkFunction[String] {
//      override def process(element: String, ctx: RuntimeContext, indexer: RequestIndexer): Unit = {
//        val json = new java.util.HashMap[String, String]
//        json.put("data", element)
//        val indexRequest: IndexRequest = Requests.indexRequest().index(indexName).`type`("_doc").source(json)
//        indexer.add(indexRequest)
//      }
//    }
//    val esSinkBuilder = new ElasticsearchSink.Builder[String](hostList, esSinkFunc)
//    esSinkBuilder.setBulkFlushMaxActions(10)
//    val esSink: ElasticsearchSink[String] = esSinkBuilder.build()
//    esSink
//  }


//  def getEsSink(indexName: String): ElasticsearchSink[String] = {
//    //new接口---> 要实现一个方法
//    val esSinkFunc: ElasticsearchSinkFunction[String] = new ElasticsearchSinkFunction[String] {
//      override def process(element: String, ctx: RuntimeContext, indexer: RequestIndexer): Unit = {
//        val json = new java.util.HashMap[String, String]
//        json.put("data", element)
//        val indexRequest: IndexRequest = Requests.indexRequest().index(indexName).`type`("_doc").source(json)
//        indexer.add(indexRequest)
//      }
//    }
//    val esSinkBuilder = new ElasticsearchSink.Builder[String](hostList, esSinkFunc)
//    esSinkBuilder.setBulkFlushMaxActions(10)
//    val esSink: ElasticsearchSink[String] = esSinkBuilder.build()
//    esSink
//  }






