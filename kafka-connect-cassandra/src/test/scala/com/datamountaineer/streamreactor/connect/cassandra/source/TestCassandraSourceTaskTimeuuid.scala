/*
 * Copyright 2017 Datamountaineer.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.datamountaineer.streamreactor.connect.cassandra.source

import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.LinkedBlockingQueue

import com.datamountaineer.streamreactor.connect.cassandra.TestConfig
import com.datamountaineer.streamreactor.connect.cassandra.config.{CassandraConfigConstants, CassandraConfigSource, CassandraSettings}
import com.datamountaineer.streamreactor.connect.queues.QueueHelpers
import com.datamountaineer.streamreactor.connect.schemas.ConverterUtil
import com.fasterxml.jackson.databind.JsonNode
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.connect.data.{Schema, Struct}
import org.apache.kafka.connect.source.SourceRecord
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{DoNotDiscover, Matchers, WordSpec}

import scala.collection.JavaConverters._
import com.datastax.driver.core.Session
import org.scalatest.BeforeAndAfterAll

/**
  */
class TestCassandraSourceTaskTimeuuid extends WordSpec 
    with Matchers
    with MockitoSugar
    with TestConfig
    with TestTableUtil
    with ConverterUtil 
    with BeforeAndAfterAll {

  var session: Session = _
  val keyspace = "source"
  var tableName: String = _

  override def beforeAll {
    session = createTableAndKeySpace(keyspace, secure = true, ssl = false)
    tableName = createTimeuuidTable(session, keyspace)
  }

  override def afterAll(): Unit = {
    session.close()
    session.getCluster.close()
  }

  "A Cassandra SourceTask should read in incremental mode with timeuuid and time slices" in {
    val session = createTableAndKeySpace(CASSANDRA_SOURCE_KEYSPACE, secure = true, ssl = false)

    val taskContext = getSourceTaskContextDefault
    val config = getCassandraConfigDefault
    val task = new CassandraSourceTask()
    task.initialize(taskContext)
    
    //start task
    task.start(config)

    insertIntoTimeuuidTable(session, keyspace, tableName, "id1", "magic_string") 

    var records = pollAndWaitTimeuuidTable(task)
    var sourceRecord = records.asScala.head
    //check a field
    var json: JsonNode = convertValueToJson(sourceRecord)
    println(json)
    json.get("string_field").asText().equals("magic_string") shouldBe true
    json.get("timeuuid_field").asText().size > 0
    json.get("int_field") shouldBe null
    
    
    insertIntoTimeuuidTable(session, keyspace, tableName, "id2", "magic_string2") 
    
    records = pollAndWaitTimeuuidTable(task)
    sourceRecord = records.asScala.head
    //check a field
    json = convertValueToJson(sourceRecord)
    println(json)
    json.get("string_field").asText().equals("magic_string2") shouldBe true
    json.get("timeuuid_field").asText().size > 0
    json.get("int_field") shouldBe null
    
    //stop task
    task.stop()
  }
  
  "A Cassandra SourceTask should read in incremental mode with timeuuid and time slices and use ignore and unwrap" in {
    val session = createTableAndKeySpace(CASSANDRA_SOURCE_KEYSPACE, secure = true, ssl = false)

    val taskContext = getSourceTaskContextDefault
    val config = getCassandraConfigWithUnwrap
    val task = new CassandraSourceTask()
    task.initialize(taskContext)
    
    //start task
    task.start(config)

    insertIntoTimeuuidTable(session, keyspace, tableName, "id1", "magic_string") 

    val records = pollAndWaitTimeuuidTable(task)
    val sourceRecord = records.asScala.head
    sourceRecord.keySchema shouldBe null
    sourceRecord.key shouldBe null
    sourceRecord.valueSchema shouldBe Schema.STRING_SCHEMA
    sourceRecord.value shouldBe "magic_string"

    //stop task
    task.stop()
  }  
  
  "A Cassandra SourceTask should throw exception when timeuuid column is not specified" in {
    val session = createTableAndKeySpace(CASSANDRA_SOURCE_KEYSPACE, secure = true, ssl = false)

    val taskContext = getSourceTaskContextDefault
    val config = getCassandraConfigWithKcqlNoPrimaryKeyInSelect
    val task = new CassandraSourceTask()
    task.initialize(taskContext)
    
    insertIntoTimeuuidTable(session, keyspace, tableName, "id1", "magic_string") 
    
    try {
      task.start(config)
      fail()
    } catch {
      case _: ConfigException => // Expected, so continue
    }
    task.stop()
  }

  private def getCassandraConfigWithKcqlNoPrimaryKeyInSelect() = {
    val myKcql = s"INSERT INTO sink_test SELECT string_field FROM $tableName PK timeuuid_field INCREMENTALMODE=timeuuid"
    getCassandraConfig(myKcql)
  }

  private def getCassandraConfigWithUnwrap() = {
    val myKcql = s"INSERT INTO sink_test SELECT string_field, timeuuid_field FROM $tableName IGNORE timeuuid_field PK timeuuid_field WITHUNWRAP INCREMENTALMODE=timeuuid"
    getCassandraConfig(myKcql)
  }
  
  private def getCassandraConfigDefault() = {
    val myKcql = s"INSERT INTO sink_test SELECT string_field, timeuuid_field FROM $tableName PK timeuuid_field INCREMENTALMODE=timeuuid"
    getCassandraConfig(myKcql)
  }
  
  private def getCassandraConfig(kcql: String) = {
    Map(
      CassandraConfigConstants.CONTACT_POINTS -> CONTACT_POINT,
      CassandraConfigConstants.KEY_SPACE -> keyspace,
      CassandraConfigConstants.USERNAME -> USERNAME,
      CassandraConfigConstants.PASSWD -> PASSWD,
      CassandraConfigConstants.KCQL -> kcql,
      CassandraConfigConstants.ASSIGNED_TABLES -> tableName,
      CassandraConfigConstants.POLL_INTERVAL -> "1000").asJava
  }

//  private def insertIntoTableTwo(session: Session, anId: String, stringValue: String) {
//    val sql = s"""INSERT INTO $CASSANDRA_SOURCE_KEYSPACE.$TABLE2
//      (id, int_field, long_field, string_field, timeuuid_field)
//      VALUES
//      ('$anId', 2, 3, '$stringValue', now());"""
//
//    // insert
//    session.execute(sql)
//
//    // wait for Cassandra write
//    Thread.sleep(1000)
//  }

  private def pollAndWaitTimeuuidTable(task: CassandraSourceTask) = {
    //trigger poll to have the readers execute a query and add to the queue
    task.poll()

    //wait a little for the poll to catch the records
    while (task.queueSize(tableName) == 0) {
      Thread.sleep(1000)
    }

    //call poll again to drain the queue
    task.poll()
  }
}

