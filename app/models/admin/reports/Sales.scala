package models.admin.reports

import scalaz._
import Scalaz._
import scalaz.effect.IO
import scalaz.EitherT._
import scalaz.Validation
import scalaz.Validation.FlatMap._
import scalaz.NonEmptyList._
import net.liftweb.json._
import io.megam.util.Time
import org.joda.time.{DateTime, Period}
import org.joda.time.format.DateTimeFormat
import models.Constants.{JSON_CLAZ, REPORTSCLAZ, REPORT_SALES}
import models.admin.{ReportInput, ReportResult}

//Reporter needs to extends trait Reporter or else you'll only get a NoOpReport
//Every report will have 3 steps (build, aggregate, and generate report data)
class Sales(ri: ReportInput) extends Reporter {

  def report: ValidationNel[Throwable, Option[ReportResult]] = {
    for {
      abt <-   build(ri.start_date, ri.end_date)  leftMap { err: NonEmptyList[Throwable] ⇒ err }
      sal <-   aggregate(abt).successNel
    } yield {
      ReportResult(REPORT_SALES, sal.map(_.map(_.toKeyList)), REPORTSCLAZ, Time.now.toString).some
    }
  }

 def build(startdate: String, enddate: String): ValidationNel[Throwable,Tuple2[Seq[models.tosca.AssemblyResult],
                Seq[models.billing.BilledhistoriesResult]]] = {
    for {
     a <- (models.tosca.Assembly.findByDateRange(startdate, enddate) leftMap { err: NonEmptyList[Throwable] ⇒ err })
     b <- (models.billing.Billedhistories.findByDateRange(startdate, enddate) leftMap { err: NonEmptyList[Throwable] ⇒ err })
   } yield {
       (a, b)
      }
  }

  def aggregate(abt: Tuple2[Seq[models.tosca.AssemblyResult], Seq[models.billing.BilledhistoriesResult]]) = {
   for {
     ba <- (abt._2.groupBy(_.assembly_id).map { case (k,v) => (k -> BillingAggregate(k,v)) }).some
     sa <-  SalesAggregate(abt._1, ba).some
    } yield {
      val a = sa.aggregate
      a
   }
  }
}

case class SalesAggregate(als: Seq[models.tosca.AssemblyResult], bh: Map[String, BillingAggregate]) {
  lazy val aggregate: Seq[SalesResult] = als.map(al =>  {
    bh.get(al.id) match {
        case Some(bhi) =>   SalesResult(al.id, al.name,al.status, al.state, bhi.start_date, bhi.end_date, bhi.sum.toString)
        case None  => SalesResult( al.id, al.name, al.status, al.state, "", "","")
      }
   })
}

case class BillingAggregate(aid: String, b:  Seq[models.billing.BilledhistoriesResult]) {

    private lazy val start_dates = b.map(_.start_date.toString)
    lazy val start_date = start_dates.sortBy({r => r}).head

    private lazy val end_dates = b.map(_.end_date.toString)
    lazy val end_date = end_dates.sortBy({r => r}).head

    lazy val sum = b.map(_.billing_amount.toInt).sum

    override def toString() = "[" + aid + " sales from " + start_date + " to " + end_date + " is:"+ sum +"]";
}

case class SalesResult( asm_id: String, asm_name: String, status: String, state: String,startdate: String, enddate: String, cost: String) {
    val X = "x"
    val Y = "y"
    val NAME = "name"
    val STATUS = "status"
    val START_DATE = "start_date"
    val END_DATE = "end_date"
    val NUMBER_OF_HOURS = "number_of_hours"

  def isEmpty(x: String) = Option(x).forall(_.isEmpty)

  def shouldZero = isEmpty(startdate) || isEmpty(enddate)

  def calculateHours =  if (shouldZero) "0"
                         else (new Period(DateTime.parse(startdate, DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss Z")),
                         DateTime.parse(startdate, DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss Z"))).toString)


  def toKeyList: models.tosca.KeyValueList = models.tosca.KeyValueList(
    Map((X -> startdate),
        (Y -> cost),
        (NAME -> asm_name),
        (STATUS -> status),
        (START_DATE -> startdate),
        (END_DATE -> enddate),
        (NUMBER_OF_HOURS -> calculateHours)))
}
