import org.springframework.dao._
import org.springframework.jdbc.core._
import org.springframework.jdbc.core.simple._
import org.springframework.jdbc.datasource._
import java.sql.ResultSet
import collection.JavaConverters._
import java.util.Arrays
import scala.util._

trait JdbcHelpers {
	type ExtractorFunction[T] = ResultSet ⇒ T
	type RowMappingFunction[T] = (ResultSet, Integer) ⇒ T
	type ArgumentType = Int
	case class TypedArgument (arg: Any, argType: ArgumentType)

	def toObject[T <: Any](v: T): Object = v match {
		case o: Option[Any] ⇒ toObject(o.orNull)
		case t: Try[Any] ⇒ toObject(t.get)
		case _ ⇒ v.asInstanceOf[Object]
	}

	def wrap[T <: Any](args: Array[T]): Array[Object] = args map toObject

	def extract[T](callback: ExtractorFunction[T]): ResultSetExtractor[Option[T]] = new ResultSetExtractor[Option[T]]() {
		def extractData(rs: ResultSet): Option[T] = if (rs.next) Option(callback(rs)) else None
	}

	def mappingRows[T](callback: RowMappingFunction[T]): RowMapper[T] = new RowMapper[T]() {
		def mapRow(rs: ResultSet, rn: Int): T = callback(rs, rn)
	}

	def get[T](db: JdbcTemplate)(query: String, args: Any*)(callback: ExtractorFunction[T]): Option[T] = db.query(query, wrap(args.toArray), extract(callback))

	def query[T](db: JdbcTemplate)(query: String, args: Any*)(callback: RowMappingFunction[T]): List[T] = db.query(query, wrap(args.toArray), mappingRows(callback)).asScala.toList

	def find[T](db: JdbcTemplate)(query: String, args: Array[TypedArgument])(callback: RowMappingFunction[T]): Option[T] = try {
		Option(db.queryForObject(query, wrap(args map { _.arg }), args map { _.argType }, mappingRows(callback)))
	} catch {
		case e: EmptyResultDataAccessException ⇒ None
	}

	def update(db: JdbcTemplate)(query: String)(args: Any*): Int = db.update(query, new ArgumentPreparedStatementSetter(wrap(args.toArray)))

	def batchUpdate[T <: Any](db: JdbcTemplate)(query: String)(args: List[Array[T]]): Array[Int] =
		db.batchUpdate(query, Arrays.asList((args.map(wrap)).toArray: _*))

}
