package com.rpetrich.szkieletor

import java.util.Collections
import scala.collection.mutable.ListBuffer
import org.apache.cassandra.locator.SimpleStrategy
import com.rpetrich.szkieletor.Conversions.keyspaceString
import com.rpetrich.szkieletor.util.LogHelper
import me.prettyprint.hector.api.ddl.{ComparatorType, ColumnType}
import me.prettyprint.hector.api.factory.HFactory
import me.prettyprint.hector.api.query.{ MultigetSliceQuery, SuperSliceQuery, MultigetSubSliceQuery, MultigetSliceCounterQuery, CounterQuery, RangeSlicesQuery }
import me.prettyprint.cassandra.serializers.{ StringSerializer, LongSerializer, BytesArraySerializer }
import java.lang.{ Long => JLong }
import scala.collection.JavaConversions._

object Conversions {
    implicit def keyspaceString(ks: Keyspace): String = ks.name
    implicit def rowString(r: Row): String = r.name
    implicit def columnfamily(cf: ColumnFamily) = cf.name
    implicit def getrows(r: Rows) = r.get
}


case class ColumnNameValue(column: Column, name: String, value: Any, isCounter: Boolean) extends LogHelper {
    def ks() = column.row.cf.ks
    def row() = column.row
    def cf() = column.row.cf

    val isSuperColumn = value match {
        case _:List[_] => true
        case _ => false
    }

    def intValue = value match {
        case i: Int    => i
        case l: Long   => l.toInt
        case n: Number => n.intValue()
        case _         => value.toString.toInt
    }

    def hColumn = value match {
        case l: Long =>
            HFactory.createColumn(name, JLong.valueOf(l), StringSerializer.get(), LongSerializer.get())
        case _ =>
            HFactory.createStringColumn(name, value.toString)
    }

    def hSuperColumn = value match {
        case list:List[String] =>
            var columnList = List(HFactory.createColumn(list.head, "", StringSerializer.get(), StringSerializer.get()))

            list.tail.foreach{ columnName:String => {
                val column = HFactory.createColumn(columnName, "", StringSerializer.get(), StringSerializer.get())
                columnList = columnList ++ List(column)
            }}

            HFactory.createSuperColumn(name, asJavaList(columnList), StringSerializer.get(), StringSerializer.get(), StringSerializer.get())

    }
}

case class Column(row: Row, name: String) {

    def of(value: Any) = ColumnNameValue(this, name, value, false)

    def inc() = {
        ColumnNameValue(this, name, 1, true)
    }

    def inc(value: Int) = {
        ColumnNameValue(this, name, value, true)
    }

    def dec(value: Int) = {
        ColumnNameValue(this, name, (value - (2 * value)), true)
    }

    def dec() = {
        ColumnNameValue(this, name, -1, true)
    }
}

case class Row(cf: ColumnFamily, name: String) {

    def has(column: String): Column = Column(this, column)
}

object Row {
    def apply(cv: ColumnNameValue): Row = cv.row
}

class Rows(cv: Option[ColumnNameValue] = None) {
    import scala.collection.mutable.ListBuffer
    val rows = new ListBuffer[ColumnNameValue]

	cv.foreach(rows += _)

    def add(cv: ColumnNameValue) = {
        rows += cv
        this
    }

    //need to be able to handle adding the two list buffers together
    //without explicitly exposing the rows unecessarly
    def ++(buffRows: Rows) = {
        rows ++= buffRows.rows
        this
    }

    def get = {
        rows.result
    }
}

object Rows {
    def apply(cv: ColumnNameValue): Rows = {
        new Rows(Some(cv))
    }
}

case class ColumnFamily(val ks: Keyspace, val name: String) extends LogHelper {
    import me.prettyprint.hector.api.factory.HFactory
    import me.prettyprint.hector.api.ddl.ComparatorType
    import Conversions._

    val cassandra = ks.cassandra

    private lazy val columnFamilyDefinition = HFactory.createColumnFamilyDefinition(ks, name, ComparatorType.UTF8TYPE)
    var isSuper = false

    def ->(row: String) = new Row(this, row)

    //get data out of this column family
    def >>(sets: (MultigetSliceQuery[String, String, String]) => Unit, proc: (String, String, String) => Unit) {
        cassandra >> (this, sets, proc)
    }

    //get data out of this super column family
    def multigetSubSliceQuery(sets: (MultigetSubSliceQuery[String, String, String, String]) => Unit, proc: (String, String, String) => Unit) {
        cassandra.multigetSubSliceQuery(this, sets, proc)
    }

    //get top level columns from super column family
    def superSliceQuery(sets: (SuperSliceQuery[String, String, String, String]) => Unit, proc: (String, String, String) => Unit) {
        cassandra.superSliceQuery(this, sets, proc)
    }

    //get rows out of this column family
    def >>>(sets: (RangeSlicesQuery[String, String, String]) => Unit, proc: (String, String, String) => Unit) {
        cassandra >>> (this, sets, proc)
    }

    //get data of this counter column family
    def >#(sets: (MultigetSliceCounterQuery[String, String]) => Unit, proc: (String, String, Long) => Unit) = {
        cassandra ># (this, sets, proc)
    }

    //get data of this counter column family
    def >%(sets: (CounterQuery[String, String]) => Unit, proc: (Long) => Unit) = {
        cassandra >% (this, sets, proc)
    }

    def <<(rows: Seq[ColumnNameValue]) = {
        cassandra << rows
    }

    def setSuper(superColumn:Boolean = true) = {
        isSuper = superColumn
        if (superColumn) columnFamilyDefinition.setColumnType(ColumnType.SUPER)
        else columnFamilyDefinition.setColumnType(ColumnType.STANDARD)
    }

    /*
     *  create the column family
     */
    def create = {
        cassandra.cluster.addColumnFamily(columnFamilyDefinition, true)
    }

    /*
     * drop the column family from the keyspace
     */
    def delete = {
        cassandra.cluster.dropColumnFamily(ks, name, true)
    }

    /*
     * truncate the data from this column family
     */
    def truncate = {
        cassandra.cluster.truncate(ks, name)
    }
}

case class Keyspace(val cassandra: Cassandra, val name: String, val replicationFactor: Int = 1) {
    private lazy val keyspaceDefinition = HFactory.createKeyspaceDefinition(name, classOf[SimpleStrategy].getName(), replicationFactor, Collections.emptyList())

    def create = {
        cassandra.cluster.addKeyspace(keyspaceDefinition, true)
    }

    def delete = {
        cassandra.cluster.dropKeyspace(name, true)
    }

    def \(cf: String) = new ColumnFamily(this, cf)
}