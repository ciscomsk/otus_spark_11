package org.example.datasource.postgres

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.connector.catalog.{SupportsRead, SupportsWrite, Table, TableCapability, TableProvider}
import org.apache.spark.sql.connector.expressions.Transform
import org.apache.spark.sql.connector.read.{Batch, InputPartition, PartitionReader, PartitionReaderFactory, Scan, ScanBuilder}
import org.apache.spark.sql.connector.write.{BatchWrite, DataWriter, DataWriterFactory, LogicalWriteInfo, PhysicalWriteInfo, WriteBuilder, WriterCommitMessage}
import org.apache.spark.sql.types._
import org.apache.spark.sql.util.CaseInsensitiveStringMap

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet, Statement}
import java.util
import scala.collection.JavaConverters._


class DefaultSource extends TableProvider {
  override def inferSchema(options: CaseInsensitiveStringMap): StructType = PostgresTable.schema

  override def getTable(
    schema: StructType,
    partitioning: Array[Transform],
    properties: util.Map[String, String]
  ): Table = new PostgresTable(properties.get("tableName")) // TODO: Error handling
}

class PostgresTable(val name: String) extends SupportsRead with SupportsWrite {
  override def schema(): StructType = PostgresTable.schema

  override def capabilities(): util.Set[TableCapability] = Set(
    TableCapability.BATCH_READ,
    TableCapability.BATCH_WRITE
  ).asJava

  override def newScanBuilder(options: CaseInsensitiveStringMap): ScanBuilder = new PostgresScanBuilder(options)

  override def newWriteBuilder(info: LogicalWriteInfo): WriteBuilder = new PostgresWriteBuilder(info.options)
}

object PostgresTable {
  val schema: StructType = new StructType().add("user_id", LongType)
}

case class ConnectionProperties(url: String, user: String, password: String, tableName: String)

/** Read */

class PostgresScanBuilder(options: CaseInsensitiveStringMap) extends ScanBuilder {
  override def build(): Scan = new PostgresScan(ConnectionProperties(
    options.get("url"), options.get("user"), options.get("password"), options.get("tableName")
  ))
}

class PostgresPartition extends InputPartition
//case class PostgresPartition2(start: Long, stop: Long) extends InputPartition
//case class PostgresPartition3(shardNum: Int) extends InputPartition

class PostgresScan(connectionProperties: ConnectionProperties) extends Scan with Batch {
  override def readSchema(): StructType = PostgresTable.schema

  override def toBatch: Batch = this

  override def planInputPartitions(): Array[InputPartition] = Array(new PostgresPartition)
//  override def planInputPartitions(): Array[InputPartition] = Array(PostgresPartition2(0, 99), PostgresPartition2(100, 200))
//  override def planInputPartitions(): Array[InputPartition] = (0 to 2).map(i => PostgresPartition3(i)).toArray

  override def createReaderFactory(): PartitionReaderFactory = new PostgresPartitionReaderFactory(connectionProperties)
}

class PostgresPartitionReaderFactory(connectionProperties: ConnectionProperties) extends PartitionReaderFactory {
  override def createReader(partition: InputPartition): PartitionReader[InternalRow] = new PostgresPartitionReader(connectionProperties)
//  override def createReader(partition: PostgresPartition2): PartitionReader[InternalRow] = new PostgresPartitionReader(connectionProperties, partition.start, partition.stop)
//  override def createReader(partition: PostgresPartition3): PartitionReader[InternalRow] = new PostgresPartitionReader(connectionProperties, partition.shardNum)
}

class PostgresPartitionReader(connectionProperties: ConnectionProperties) extends PartitionReader[InternalRow] {
//class PostgresPartitionReader(connectionProperties: ConnectionProperties) extends PartitionReader[InternalRow] {
  private val connection: Connection = DriverManager.getConnection(
    connectionProperties.url, connectionProperties.user, connectionProperties.password
  )
  private val statement: Statement = connection.createStatement()
  private val resultSet: ResultSet = statement.executeQuery(s"select * from ${connectionProperties.tableName}")
  // offset 100 + limit 200
  //  private val resultSet = statement.executeQuery(s"select * from ${connectionProperties.tableName}")

  override def next(): Boolean = resultSet.next()

  override def get(): InternalRow = InternalRow(resultSet.getLong(1))

  override def close(): Unit = connection.close()
}

/** Write */

class PostgresWriteBuilder(options: CaseInsensitiveStringMap) extends WriteBuilder {
  override def buildForBatch(): BatchWrite = new PostgresBatchWrite(ConnectionProperties(
    options.get("url"), options.get("user"), options.get("password"), options.get("tableName")
  ))
}

class PostgresBatchWrite(connectionProperties: ConnectionProperties) extends BatchWrite {
  override def createBatchWriterFactory(physicalWriteInfo: PhysicalWriteInfo): DataWriterFactory =
    new PostgresDataWriterFactory(connectionProperties)

  override def commit(writerCommitMessages: Array[WriterCommitMessage]): Unit = {}

  override def abort(writerCommitMessages: Array[WriterCommitMessage]): Unit = {}
}

class PostgresDataWriterFactory(connectionProperties: ConnectionProperties) extends DataWriterFactory {
  override def createWriter(partitionId: Int, taskId:Long): DataWriter[InternalRow] =
    new PostgresWriter(connectionProperties)
}

object WriteSucceeded extends WriterCommitMessage

class PostgresWriter(connectionProperties: ConnectionProperties) extends DataWriter[InternalRow] {

  val connection: Connection = DriverManager.getConnection(
    connectionProperties.url,
    connectionProperties.user,
    connectionProperties.password
  )
  val statement = "insert into users (user_id) values (?)"
  val preparedStatement: PreparedStatement = connection.prepareStatement(statement)

  override def write(record: InternalRow): Unit = {
    val value = record.getLong(0)
    preparedStatement.setLong(1, value)
    preparedStatement.executeUpdate()
  }

  override def commit(): WriterCommitMessage = WriteSucceeded

  override def abort(): Unit = {}

  override def close(): Unit = connection.close()
}

