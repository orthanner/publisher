import collection.JavaConverters._
import akka.util.ByteString
import org.apache.commons.dbcp2._
import scala.util._
import akka.util._
import com.typesafe.config._
import akka.actor._
import org.springframework.jdbc.core._
import org.springframework.jdbc.core.simple._
import org.springframework.jdbc.datasource._
import scala.util.matching._

abstract class Handler(pattern: Regex) {
	val predicate = pattern.pattern.asPredicate
	def accept(message: String): Boolean = predicate.test(message)
	def apply(src: ActorRef, owner: ActorRef, db: JdbcTemplate, config: Config, message: String, content: ByteString): Try[String]
}

