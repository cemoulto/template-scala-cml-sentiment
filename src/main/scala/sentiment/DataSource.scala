package sentiment

import cml._
import cml.algebra.Floating._
import grizzled.slf4j.Logger
import io.prediction.controller._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import scala.io.Source
import scala.util.parsing.combinator.RegexParsers

case class DataSourceParams(
  fraction: Double,
  batchSize: Int
) extends Params

class DataSource(params: DataSourceParams)
  extends PDataSource[TrainingData, EmptyEvaluationInfo, Query, Result] with RegexParsers {

  @transient lazy val logger = Logger[this.type]

  override def readTraining(sc: SparkContext): TrainingData = {
    val data = readPTB("data/train.txt").grouped(params.batchSize).toSeq
    val rdd = sc.parallelize(data)
      .sample(withReplacement = false, fraction = params.fraction).cache()
    TrainingData(rdd)
  }

  override def readEval(sc: SparkContext): Seq[(TrainingData, EmptyEvaluationInfo, RDD[(Query, Result)])] = {
    val data = readPTB("data/train.txt").grouped(params.batchSize).toSeq
    val training = sc.parallelize(data)
      .sample(withReplacement = false, fraction = params.fraction).cache()
    val eval = sc.parallelize(readPTB("data/test.txt")).map(t => (t._1: Query, t._2))
    Seq((TrainingData(training), new EmptyEvaluationInfo(), eval))
  }

  def readPTB(path: String): Seq[(TreeQuery, Result)] = {
    val data = Source
      .fromFile(path)
      .getLines()
      .toSeq

    data
      .map(parse(tree, _))
      .flatMap {
        case Success(v, _) => Some(v)
        case NoSuccess(msg, _) => {
          println(msg)
          None
        }
      }
      // Filter out neutral sentences like the RNTN paper does.
      .filter(t => Sentiment.choose(t.accum) != "2")
      .map(t => (TreeQuery(Tree.accums.map(t)(_ => ())), Result(t)))
  }

  def sentVec(label: String): Sentiment.Vector[Double] =
    Sentiment.space.tabulatePartial(Map(Sentiment.classes(label) -> 1d))

  def tree: Parser[Tree[Sentiment.Vector[Double], String]] =
    ("(" ~ string ~ string ~ ")" ^^ {
      case _ ~ label ~ word ~ _ => Leaf(sentVec(label), word)
    }) | ("(" ~ string ~ tree ~ tree ~ ")" ^^ {
      case _ ~ label ~ left ~ right ~ _ => Node(left, sentVec(label), right)
    })

  def string: Parser[String] = "[^\\s()]+"r
}

case class TrainingData(
  get: RDD[Seq[(TreeQuery, Result)]]
) extends Serializable
