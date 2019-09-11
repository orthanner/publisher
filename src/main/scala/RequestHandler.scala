import akka.actor._
import akka.io._
import akka.util._
import akka.agent._
import com.typesafe.config._
import java.io._
import java.lang.{ Boolean ⇒ Bool }
import java.net._
import java.nio._
import java.nio.channels._
import java.nio.file._
import java.sql.{ ResultSet, SQLException }
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic._
import javax.sql.DataSource
import org.apache.commons.codec.binary._
import org.springframework.jdbc.core._
import org.springframework.jdbc.core.simple._
import org.springframework.jdbc.datasource._
import org.springframework.transaction._
import org.springframework.transaction.support._
import scala.async.Async.{ async, await }
import scala.collection.JavaConverters._
import scala.concurrent._
import scala.util._
import scala.util.matching._
import scala.language.postfixOps

object RequestHandler {
	val LINE_DELIMITER = Seq(13, 10)

	val HANDSHAKE = "usercert (?<cert>[\\w]+)".r
	val PUBLISH = "publish (?<token>[A-F0-9]+) (?<global>true|false) (?<local>true|false) (?<gallery>true|false) (?<title>.+)".r
	val EDIT = "edit (?<token>[A-F0-9]+) (?<id>[\\d]+) (?<global>true|false) (?<local>true|false) (?<gallery>true|false) (?<title>.+)".r
	val ANNOTATION = "anno (?<length>[\\d]+)".r
	val CONTENT = "content (?<length>[\\d]+)".r
	val ENTITY = "(?<sort>file|image|logo) (?<length>[\\d]+) (?<name>\\S+) (?<originalName>\\S+)".r
	val FETCH = "fetch (?<type>file|image|logo|article) (?<id>[\\d]+)".r
	val DELETE_ARTICLE = "delete article (?<id>[\\w.]+) as (?<token>[A-F0-9]+)".r
	val DELETE = "delete (?<type>image|file) (?<id>[\\w.]+)".r
	val DELETE_BY_NAME = "delete (?<type>image|file) /(?<name>.+)".r
	val DELETE_LOGO = "delete logo".r
	val DATE = "date (?<date>[0-9]{1,2} [\\S]+ [0-9]{4})".r
  	val RENAME = "rename (?<type>image|file) (?<id>[\\w]+) (?<to>.+)".r
	val COMMIT = "commit".r
	val ABORT = "abort".r
	val LIST_BY_DATE = "list (?<date>\\d{1,4}-\\d{1,2}-\\d{1,2})".r
	val LIST_BY_PERIOD = "list (?<start>\\d{1,4}-\\d{1,2}-\\d{1,2}) (?<end>\\d{1,4}-\\d{1,2}-\\d{1,2})".r

  	def success(target: ActorRef)(msg: String): Unit = target ! Tcp.Write(ByteString("+%s\r\n" format msg))
  	def failure(target: ActorRef)(msg: String): Unit = target ! Tcp.Write(ByteString("-%s\r\n" format msg))
}

class RequestHandler(src: ActorRef, db: JdbcTemplate, client: InetSocketAddress, config: Config) extends Actor with ActorLogging with TxHelpers with JdbcHelpers with Runnable {
	import Tcp._
	import RequestHandler._
	import Base64._
	import context.dispatcher
	import org.json4s._
	import org.json4s.JsonDSL._
	import org.json4s.jackson.JsonMethods._

	case class Attribute(`type`: String, value: ByteString)
	case object Ack extends Event

	val inserter = new SimpleJdbcInsert(db).withTableName("news").usingColumns("date", "owner", "owner_unit", "author_link", "title", "annotation", "text", "img", "local", "global", "obtained", "apply", "author", "author_unit", "uuid", "nextgen").usingGeneratedKeyColumns("id")
	val fileInserter = new SimpleJdbcInsert(db).withTableName("files").usingColumns("sort", "name", "container", "original_name").usingGeneratedKeyColumns("id")
	val txManager = new DataSourceTransactionManager(db.getDataSource())
	val txTemplate = new TransactionTemplate(txManager)
	val alive = new Switch(false)
	val storing = new Switch(false)
	val packets = new ConcurrentLinkedQueue[WriteRequest]()
	val current = new AtomicReference[File]()

  	val _success = success(src) _
  	val _failure = failure(src) _

/**
*  These come from prefixes, except the first one being just "byte". Each next one is 1024*previous
*/
	val multipliers = "bkmgtpezy"

	val DATE_FORMAT = new java.text.SimpleDateFormat("yyyy-MM-dd")

	lazy val storePath = new File(config.getString("files.storePath")).toPath

	val edit = update(db)("update news set title=?, annotation=?, text=?, img=?, local=?, global=?, date=? where id=?") _

	var certificate: Option[String] = None

	def tag = certificate map { "key:%s" format _ } getOrElse "ip:%s".format(client.getHostString())

	override def preStart(): Unit = {
		alive.switchOn
		new Thread(this).start()
	}

	override def postStop(): Unit = {
		alive.switchOff
	}

	override def run(): Unit = {
		while (alive.isOn) {
			packets.poll() match {
				case r: WriteRequest ⇒
					Try {
						if (r.write(log)) {
							_success("stored")
							storing.switchOff
						} else
							storing.switchOn
					} recover {
						case e: IOException ⇒ {
							log.error("fuck up while storing uploaded file", e)
							packets.clear()
              						_failure(e.getMessage())
							r.file.delete
						}
					}
				case null ⇒
			}
			Thread.`yield`()
		}
	}

	final def toJavaMap[T](m: Map[T, Any]): java.util.Map[T, Object] = m map { case (k, v) ⇒ (k, v.asInstanceOf[Object]) } asJava

	def getAttributes(token: String, attrs: String*): Future[Map[String, Attribute]] = async {
		val s = new Socket(config.getString("auth.host"), config.getInt("auth.port"))
		val w = new BufferedWriter(new OutputStreamWriter(new BufferedOutputStream(s.getOutputStream()), "UTF-8"))
		val r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))
		val m = attrs map { attr ⇒
			w.write("get %s@%s/%s\r\n".format(token, tag, attr))
			w.flush
			log.info("sent auth request")
			val reply = r.readLine().trim()
			reply.charAt(0) match {
				case '+' ⇒ {
					reply.substring(1) match {
						case "$" ⇒ (attr, Attribute("", ByteString.empty))
						case v ⇒ (attr, Attribute(v.substring(0, v.indexOf(":")), ByteString(decodeBase64(v.substring(v.indexOf(":") + 1)))))
					}
				}
				case '-' ⇒ null
			}
		} filter { _ != null } toMap;
		s.close()
		m
	}

	def checkPermission(token: String, permission: String): Future[Unit] = async {
		val s = new Socket(config.getString("auth.host"), config.getInt("auth.port"))
		val w = new BufferedWriter(new OutputStreamWriter(new BufferedOutputStream(s.getOutputStream()), "UTF-8"))
		val r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))
		w.write("check %s %s %s\r\n".format(token, tag, permission))
		w.flush
		log.info(log.format("checking {}:{} for {}@{}/{}", config.getString("auth.host"), config.getInt("auth.port"), token, tag, permission))
		val reply = r.readLine().trim().charAt(0) == '+'
		s.close()
		log.info(log.format("check {}:{} for {}@{}/{}: {}", config.getString("auth.host"), config.getInt("auth.port"), token, tag, permission, reply))
		if (!reply) throw new java.security.AccessControlException("%s is not allowed for %s@%s".format(permission, token, tag))
	}

	def createFile(sort: String, id: String, name: String, originalName: String, size: Long): Future[Attachment] = async {
		val folder = new File(new File(storePath.toFile, sort.concat("s")), id)
		if (storePath.toFile.getFreeSpace < size)
			throw new IOException("Not enough space to store file")
		folder.mkdirs()
		Attachment(name, originalName, size, new File(folder, name))
	}

	def openFile(file: Attachment) = (file, new FileOutputStream(file.file).getChannel())

	def receive = raw_receive(ByteString.empty)

	private def toSize(value: String): Long = {
		def toSizeImpl(acc: Long, power: Int): Long = if (power == 0) acc else toSizeImpl(acc * 1024, power - 1)

		val unit = value.last
		val pos = multipliers.indexOf(unit)
		if (pos == -1)
			value.toLong
		else
			toSizeImpl(value.dropRight(1).toLong, pos)
	}

	def maxFileSize(s: ByteString): Long = toSize(if (s.isEmpty) config.getString("files.maxSize") else s.utf8String)

	private def cancel(article: ArticleRequest) = {
		article.logo.filter { f ⇒ f.id.isEmpty } foreach { _.file.delete() }
		article.files.filter { f ⇒ f.id.isEmpty } foreach { _.file.delete() }
		article.images.filter { f ⇒ f.id.isEmpty } foreach { _.file.delete() }
		context stop self
	}

	val normalMode = Map(PUBLISH -> publish _, EDIT -> edit _, HANDSHAKE -> handshake _, FETCH -> fetch _)

	//private def publish(message: String, content: ByteString): Unit = {
	private def publish(token: String, global: String, local: String, gallery: String, title: String, content: ByteString): Unit = {
		//val PUBLISH(token, global, local, gallery, title) = message
		checkPermission(token, "post") flatMap { _ ⇒
			getAttributes(token, "login", "title", "files.maxSize")
		} onComplete {
			case Success(attrs) ⇒
				val article = ArticleRequest(None, token, global.toBoolean, local.toBoolean, gallery.toBoolean, attrs("login").value.utf8String, attrs("title").value.utf8String, title.trim())
				context become publishing(article, maxFileSize(attrs("files.maxSize").value), content)
				self ! Received(ByteString.empty)
				_success(article.uuid)
				log.info("publishing")
			case Failure(error) ⇒
				context become raw_receive(content)
				_failure(error.getMessage())
				log.error("not allowed")
		}
	}

	//private def fetch(message: String, content: ByteString): Unit = {
	private def fetch(sort: String, id: String, content: ByteString): Unit = {
		//val FETCH(sort, id) = message
		sort match {
			case "article" ⇒
				get(db)("select * from news where id=?", id.toInt) { row: ResultSet ⇒
					val logo = get(db)("select * from files where sort='logo' and container=?", id.toInt) { rs ⇒
						val name = rs.getString("name")
						val path = storePath.resolve(name)
						val originalName = rs.getString("original_name")
						/*
						надо решить проблему с доступом к файлам, сохранённым ранее (в прежней версии портала). Проще всего воспользоваться тем, что объект не обязан ссылаться на реально существующий файл
						*/
						if (path.toFile.exists)
							new Attachment(name, originalName, Files.size(path), path.toFile)
						else
							new Attachment(name, originalName, 0, new File(name))
					}
					ArticleRequest(Some(id.toInt), null, row.getBoolean("global"), row.getBoolean("local"), row.getBoolean("gallery"), row.getString("owner"), row.getString("owner_unit"), row.getString("title"), row.getString("date"), row.getString("annotation"), row.getString("text"), row.getString("uuid"), logo)
				} match {
					case Some(article) ⇒
						val files = getFiles(db, id.toInt)
						val json = ("id" -> article.id.get) ~
							("global" -> article.global) ~
							("local" -> article.local) ~
							("title" -> article.title) ~
							("gallery" -> article.gallery) ~
							("annotation" -> article.annotation) ~
							("content" -> article.text) ~
							("date" -> article.date) ~
							("uuid" -> article.uuid) ~
							("logo" -> files.get("logo").map { _ map { f ⇒ ("id" -> f._1) ~ ("name" -> f._2) ~ ("originalName" -> f._3) } }) ~
							("files" -> files.get("file").map { _ map { f ⇒ ("id" -> f._1) ~ ("name" -> f._2) ~ ("originalName" -> f._3) } }) ~
							("images" -> files.get("image").map { _ map { f ⇒ ("id" -> f._1) ~ ("name" -> f._2) ~ ("originalName" -> f._3) } })
						src ! _success(compact(render(json)))
					case None ⇒ src ! _failure("%s not found" format id)
				}
			case _ ⇒
				get(db)("select from files where sort=? and id=?", sort, id.toLong) { rs: ResultSet ⇒
					val name = rs.getString("name")
					storePath.resolve(name)
				} match {
					case Some(path) ⇒
						if (Files.exists(path)) {
							context become waiting(content, path)
							_success("%d" format Files.size(path))
						} else {
							context become raw_receive(content)
							_failure("file not found")
						}
					case None ⇒
						context become raw_receive(content)
						_failure("not found")
				}
		}
	}

	//private def edit(message: String, content: ByteString): Unit = {
	private def edit(token: String, id: String, global: String, local: String, gallery: String, title: String, content: ByteString): Unit = {
		//val EDIT(token, id, global, local, gallery, title) = message
		checkPermission(token, "manage.all") map { _ ⇒ false } recoverWith {
			case e: java.security.AccessControlException ⇒
				checkPermission(token, "manage.own") map { _ ⇒ true }
		} flatMap { strict ⇒
			getAttributes(token, "login", "title", "files.maxSize") map { attrs ⇒
				log.info("fetching article {}", id)
				get(db)("select * from news where id=?", id.toInt) { row: ResultSet ⇒
					val article = ArticleRequest(Some(id.toInt), token, global.toBoolean, local.toBoolean, gallery.toBoolean, row.getString("owner"), row.getString("owner_unit"), title, row.getString("date"), row.getString("annotation"), row.getString("text"), row.getString("uuid"))
					log.info("fetching logo")
					val logo = get(db)("select * from files where sort='logo' and container=? and not deleted order by id desc limit 1", id.toInt) { rs ⇒
						val name = rs.getString("name")
						val path = storePath.resolve(name)
						val lid = rs.getLong("id")
						val originalName = rs.getString("original_name")
						/*
						надо решить проблему с доступом к файлам, сохранённым ранее (в прежней версии портала). Проще всего воспользоваться тем, что объект не обязан ссылаться на реально существующий файл
						*/
						log.info("fetched logo {} with id {}", path, lid)
						if (path.toFile.exists)
							new Attachment(name, originalName, Files.size(path), path.toFile, Some(lid))
						else
							new Attachment(name, originalName, 0, new File(name), Some(lid))
					}
					log.info("returning")
					article.setLogo(logo)
				} match {
					case Some(article) ⇒
						//TODO: new File(row.getString("name")) возвращает ссылку на несуществующий файл, поскольку использует неверный каталог (игнорирует storePath). Надо показать ему, где находятся старые файлы, или конвертировать из старого формата
						//val files = getFiles(db, article.id.get)
						log.info("getting files")
						val files = query(db)("select id, sort, name, original_name from files where container=? and not deleted", article.id.get) {
							(row: ResultSet, index: Integer) ⇒ (row.getString("sort"), row.getLong("id"), new File(storePath.toFile, row.getString("name")), row.getString("original_name"))
						} groupBy (_._1) map {
							case (k, l) ⇒ (k, l map { case (sort, id, file, originalName) ⇒ Attachment(file.getName(), originalName, file.length, file, Some(id)) })
						}
						log.info("returning article {}", id)
						(article.attachFiles(files.get("file").getOrElse(Seq.empty[Attachment])).attachImages(files.get("image").getOrElse(Seq.empty[Attachment])), maxFileSize(attrs("files.maxSize").value))
					case None ⇒ throw new NoSuchElementException("Article not found")
				}
			}
		} onComplete {
			case Success(data) ⇒
				log.info("switched to editing of article {}", id)
				val (article: ArticleRequest, limit: Long) = data
				context become publishing(article, limit, ByteString.empty)
				_success("ready")
			case Failure(error) ⇒
				context become raw_receive(content)
				_failure("%s: %s".format(error.getClass.getName(), error.getMessage()))
		}
	}

	private def handshake(cert: String, content: ByteString): Unit = {
		certificate = Some(cert)
		context become raw_receive(content)
		_success("using certificate")
	}

	private def unknownCommand(message: String, content: ByteString): Unit = {
		context become raw_receive(content)
		_failure("Invalid command: %s\r\n" format message)
	}

	private def listByDate(date: String): Unit = {
		val articles = query(db)("select id, global, local, gallery, title, owner, created, date from news where cast(created as date)=? order by created asc", new java.sql.Date(DATE_FORMAT.parse(date).getTime)) { (row: ResultSet, index: Integer) ⇒
			(row.getInt("id").toString -> ("global" -> row.getBoolean("global")) ~ ("local" -> row.getBoolean("local")) ~ ("gallery" -> row.getBoolean("gallery")) ~ ("title" -> row.getString("title")) ~ ("owner" -> row.getString("owner")) ~ ("created" -> DATE_FORMAT.format(row.getDate("created"))) ~ ("date" -> row.getString("date")))
		} toMap;
		_success("%s".format(compact(render(articles))))
	}

	private def listByPeriod(start: String, end: String): Unit = {
		val articles = query(db)("select id, global, local, gallery, title, owner, created, date from news where cast(created as date) between ? and ? order by created asc", new java.sql.Date(DATE_FORMAT.parse(start).getTime), new java.sql.Date(DATE_FORMAT.parse(end).getTime)) { (row: ResultSet, index: Integer) ⇒
			(row.getInt("id").toString -> ("global" -> row.getBoolean("global")) ~ ("local" -> row.getBoolean("local")) ~ ("gallery" -> row.getBoolean("gallery")) ~ ("title" -> row.getString("title")) ~ ("owner" -> row.getString("owner")) ~ ("created" -> DATE_FORMAT.format(row.getDate("created"))) ~ ("date" -> row.getString("date")))
		} toMap;
		_success("%s".format(compact(render(articles))))
	}

	def raw_receive(data: ByteString): Receive = {
		case PeerClosed ⇒ context stop self
		case Closed ⇒ context stop self
		case Received(input) ⇒ {
			var content = data ++ input
			val pos = content.indexOfSlice(LINE_DELIMITER)
			if (pos != -1) {
				val message = content.slice(0, pos).utf8String.trim()
				content = content.drop(pos + 2)
				log.info("got msg: {}", message)
				message match {
					case HANDSHAKE(cert) ⇒ handshake(cert, content)
					case EDIT(token, id, global, local, gallery, title) ⇒ edit(token, id, global, local, gallery, title, content)
					case FETCH(sort, id) ⇒ fetch(sort, id, content)
					case PUBLISH(token, global, local, gallery, title) ⇒ publish(token, global, local, gallery, title, content)
					case LIST_BY_DATE(date) ⇒ listByDate(date)
					case LIST_BY_PERIOD(start, end) ⇒ listByPeriod(start, end)
					case ABORT() ⇒
						_success("Bye")
						src ! Close
				}
			} else
				context become raw_receive(content)
		}
	}

	def waiting(data: ByteString, path: Path): Receive = {
		case Received(input) ⇒
			var content = data ++ input
			val pos = content.indexOfSlice(LINE_DELIMITER)
			if (pos != -1) {
				val message = content.slice(0, pos).utf8String.trim()
				content = content.drop(pos + 2)
				message.charAt(0) match {
					case '+' ⇒
						src ! WriteFile(path.toString, 0, Files.size(path), Ack)
					case '-' ⇒
						log.warning("client cancelled download")
						context become raw_receive(content)
					case _ ⇒
						_failure("-WTF???\r\n")
						context become raw_receive(content)
				}
			}
		case CommandFailed(cmd: WriteFile) ⇒
			src ! Close
		//src ! Write(ByteString("-Failed to send file???\r\n"))
		case Ack ⇒
			context become raw_receive(data)
	}

	private def removeFile(article: ArticleRequest, sort: String)(predicate: Attachment ⇒ Boolean): Boolean = {
		val c = sort match {
			case "file" ⇒ article.files
			case "image" ⇒ article.images
		}
		val f = c.find(predicate)
		f foreach { _.deleted = true }
		f.nonEmpty
	}

	private def getFiles(db: JdbcTemplate, container: Int): Map[String, Seq[(Long, String, String)]] = {
		query(db)("select * from files where container=? and not deleted", container) {
			case (row: ResultSet, index: Integer) ⇒
				(row.getString("sort"), row.getLong("id"), row.getString("name"), row.getString("original_name"))
		} groupBy { _._1 } mapValues { _ map { v ⇒ (v._2, v._3, v._4) } }
	}

	private def transition(length: Long, behavior: Receive): Unit = {
		if (length > 0)
			context become behavior
		else
			log.warning("Zero length")
		_success("")
	}

	private def annotation(article: ArticleRequest, fileSizeLimit: Long, message: String, data: ByteString): Unit = {
		val ANNOTATION(l) = message
		val length = l.toLong
		transition(length, annotating(article, fileSizeLimit, length, data))
	}

	private def text_content(article: ArticleRequest, fileSizeLimit: Long, message: String, data: ByteString): Unit = {
		val CONTENT(l) = message
		val length = l.toLong
		transition(length, writing(article, fileSizeLimit, length, data))
	}

	private def entity(article: ArticleRequest, fileSizeLimit: Long, message: String, content: ByteString): Unit = {
		val ENTITY(sort, l, name, originalName) = message
		if ((l.toLong > fileSizeLimit) & (fileSizeLimit >= 0)) {
			_failure("TooLargeFileException: %s" format fileSizeLimit)
		} else if (l.toLong < 1) {
			_failure("zero length")
		} else createFile(sort, article.uuid, ByteString(decodeBase64(name)).utf8String, ByteString(decodeBase64(originalName)).utf8String, l.toLong) map (openFile) andThen {
			case Success(result) ⇒ result match {
				case (file: Attachment, channel: FileChannel) ⇒
					context become storing(article, fileSizeLimit, sort, l.toLong, file, channel)
			}
			case Failure(_) ⇒ context become publishing(article, fileSizeLimit, content)
		} map {
			x ⇒ "+%s\r\n" format x._1.asInstanceOf[Attachment].file.getAbsolutePath()
		} recover {
			case e: Exception ⇒ "-%s: %s\r\n".format(e.getClass.getName, e.getMessage)
		} onSuccess {
			case r: String ⇒ src ! Write(ByteString(r))
		}
	}

	private def date(article: ArticleRequest, fileSizeLimit: Long, message: String, content: ByteString): Unit = {
		val DATE(date) = message
		article.date = date
		context become publishing(article, fileSizeLimit, content)
		_success("")
	}

	private def delete_logo(article: ArticleRequest, fileSizeLimit: Long, message: String, content: ByteString): Unit = {
		article.logo foreach { _.deleted = true }
		_success("")
	}

	private def deleteFile(article: ArticleRequest, message: String): Boolean = message match {
		case DELETE(sort, id) ⇒
			removeFile(article, sort) { _.id map { _ == id.toLong } getOrElse false }
		case DELETE_BY_NAME(sort, name) ⇒
			removeFile(article, sort) { _.name.endsWith(name) }
	}

	private def delete(article: ArticleRequest, fileSizeLimit: Long, message: String, content: ByteString): Unit =
		if (deleteFile(article, message))
			_success("")
		else
			_failure("")

	private def rename(article: ArticleRequest, fileSizeLimit: Long, message: String, content: ByteString): Unit = {
                val RENAME(sort, id, to) = message
                val collection = sort match {
                        case "file" => article.files
                        case "image" => article.images
                }
                collection find { _.id.map { String.valueOf(_) }.getOrElse("").compareTo(message) == 0 } match {
                        case Some(file) =>
                                file.originalName = to
                                file.renamed = true
                                _success("")
                        case None =>
                                _failure("")
                }
        }

	private def commit(article: ArticleRequest, fileSizeLimit: Long, message: String, content: ByteString): Unit = {
		log.info("committing")
		withTransaction(txTemplate) { status ⇒
			log.info("Store path: {}", storePath)
			log.info("Logo path: {}", article.logo.map { a: Attachment ⇒ a.file.toPath })
			val m = Map("date" -> article.date, "owner" -> article.author, "owner_unit" -> article.author_unit, "author_link" -> article.link, "title" -> article.title, "annotation" -> article.annotation, "text" -> article.text, "img" -> (article.logo.map { a: Attachment ⇒ a.file.toPath } map { f: Path ⇒ if (f.isAbsolute) storePath.relativize(f).toString else f.toString }).orNull, "local" -> article.local, "global" -> article.global, "obtained" -> false, "apply" -> true, "author" -> article.author, "author_unit" -> article.author_unit, "uuid" -> article.uuid, "nextgen" -> true)
			val id: Int = article.id match {
				case Some(_id) ⇒
					edit(Seq(m("title"), m("annotation"), m("text"), m("img"), m("local"), m("global"), m("date"), _id))
					_id
				case None ⇒ inserter.executeAndReturnKey(toJavaMap(m)).intValue
			}
			log.info("removing stale files for article {}", id)
			article.logo.filter { f ⇒ f.id.nonEmpty && f.deleted } foreach { file ⇒
				update(db)("delete from files where id=?")(file.id)
				file.file.delete()
			}
			val removedFiles = (article.files ++ article.images) filter { f ⇒ f.id.nonEmpty && f.deleted } map { f ⇒ Array(f.id.get) }
			try {
				batchUpdate(db)("update files set container=null where id=?")(removedFiles)
			} catch {
				case e: org.springframework.jdbc.BadSqlGrammarException ⇒
					e.getCause match {
						case s: java.sql.BatchUpdateException ⇒ throw s.getNextException
						case _ ⇒ throw e
					}
				case ex: Exception ⇒ throw ex
			}
			val renamedFiles = (article.files ++ article.images) filter { f ⇒ f.id.nonEmpty && f.renamed && !f.deleted } map { f ⇒ Array(f.originalName, f.id.get) }
			try {
				batchUpdate(db)("update files set original_name=? where id=?")(renamedFiles)
			} catch {
				case e: org.springframework.jdbc.BadSqlGrammarException ⇒
					e.getCause match {
						case s: java.sql.BatchUpdateException ⇒ throw s.getNextException
						case _ ⇒ throw e
					}
				case ex: Exception ⇒ throw ex
			}
			val filesToDelete = query(db)("select distinct name from files where sort='logo' and container=?", id) {
				case (rs: ResultSet, n: Integer) ⇒
					rs.getString("name")
			} filter { _.compareTo(article.logo.map { _.name } getOrElse "") != 0 } map { storePath.resolve(_) }
			article.logo.map(_.id).flatten match {
				case None ⇒ log.info("removed {} files", update(db)("delete from files where sort='logo' and container=?")(id))
				case Some(lid) ⇒ log.info("removed {} files", update(db)("delete from files where sort='logo' and container=? and id<>?")(id, lid))
			}
			List((article.files, "file"), (article.images, "image"), (article.logo.toSeq, "logo")) map { c ⇒
				(c._1.filter { _.id.isEmpty }, c._2)
			} foreach {
				case (c, s) ⇒
					try {
						fileInserter.executeBatch(c.foldLeft(Array.empty[java.util.Map[String, Object]]) { (acc, f) ⇒
							val p = f.file.toPath
							toJavaMap(Map("sort" -> s, "name" -> (if (p.isAbsolute) storePath.relativize(p) else p).toString, "container" -> id, "original_name" -> f.originalName)) +: acc
						}: _*)
					} catch {
						case e: org.springframework.jdbc.BadSqlGrammarException ⇒
							e.getCause match {
								case s: java.sql.BatchUpdateException ⇒ throw s.getNextException
								case _ ⇒ throw e
							}
						case ex: Exception ⇒ throw ex
					}
			}
			log.info("flushing {}", id)
			status.flush
			filesToDelete filter { f: Path ⇒ Files.exists(f) } foreach { f: Path ⇒
				Files.delete(f)
			}
			log.info("committed {}", id)
			article.id = Some(id)
			(article.clearAttachments, getFiles(db, id))
		} match {
			case Success(reply) ⇒
				val tree = ("id" -> reply._1.id) ~
					("annotation" -> reply._1.annotation) ~
					("content" -> reply._1.text) ~
					("title" -> reply._1.title) ~
					("date" -> reply._1.date) ~
					("logo" -> reply._2.get("logo").map { _ map { f ⇒ ("id" -> f._1) ~ ("name" -> f._2) } }) ~
					("images" -> reply._2.get("image").map { _ map { f ⇒ ("id" -> f._1) ~ ("name" -> f._2) } }) ~
					("files" -> reply._2.get("file").map { _ map { f ⇒ ("id" -> f._1) ~ ("name" -> f._2) } })
				_success("%s" format compact(render(tree)))
			case Failure(e) ⇒
				log.error(e, "Transaction failed")
				_failure("%s" format e)
				article.logo.filter { _.id.isEmpty } foreach { _.file.delete() }
				article.files.filter { _.id.isEmpty } foreach { _.file.delete() }
				article.images.filter { _.id.isEmpty } foreach { _.file.delete() }
		}
		context become raw_receive(content)
	}

	private def abort(article: ArticleRequest, fileSizeLimit: Long, message: String, content: ByteString): Unit = {
		log.info("aborted publishing")
		context become raw_receive(content)
		async {
			(article.logo.toSeq ++ article.files ++ article.images) filter { _.id.isEmpty } foreach { _.file.delete() }
			Seq("logo", "image", "file") foreach { sort ⇒
				val folder = new File(new File(storePath.toFile, sort.concat("s")), article.uuid)
				folder.delete()
			}
		}
		_success("")
	}

	def publishing(article: ArticleRequest, fileSizeLimit: Long, data: ByteString): Receive = {
		case PeerClosed ⇒ cancel(article)
		case Received(input) ⇒ {
			var content = data ++ input
			val pos = content.indexOfSlice(LINE_DELIMITER)
			if (pos != -1) {
				val message = content.slice(0, pos).utf8String.trim()
				content = content.drop(pos + LINE_DELIMITER.size)
				log.info("got request: {}", message)
				message match {
					case ANNOTATION(_*) ⇒ annotation(article, fileSizeLimit, message, content)
					case CONTENT(_*) ⇒ text_content(article, fileSizeLimit, message, content)
					case ENTITY(_*) ⇒ entity(article, fileSizeLimit, message, content)
					case DATE(_*) ⇒ date(article, fileSizeLimit, message, content)
					case DELETE_LOGO(_*) ⇒ delete_logo(article, fileSizeLimit, message, content)
					case DELETE(_*) ⇒ delete(article, fileSizeLimit, message, content)
					case DELETE_BY_NAME(_*) ⇒ delete(article, fileSizeLimit, message, content)
					case ABORT(_*) ⇒ abort(article, fileSizeLimit, message, content)
					case COMMIT(_*) ⇒ commit(article, fileSizeLimit, message, content)
                                        case RENAME(_*) => rename(article, fileSizeLimit, message, content)
					case _ ⇒
						context become publishing(article, fileSizeLimit, content)
						_failure("invalid command")
				}
			} else
				context become publishing(article, fileSizeLimit, content)
		}
	}

	def annotating(article: ArticleRequest, fileSizeLimit: Long, remaining: Long, acc: ByteString): Receive = {
		case Received(data) if remaining <= data.length ⇒ //Annotation completed
			log.info("annotation completed")
			context become publishing(article.annotate((acc ++ data.take(Math.min(remaining, Integer.MAX_VALUE).toInt)).utf8String.trim()), fileSizeLimit, ByteString.empty)
			_success("")
		case Received(data) if remaining > data.length ⇒ context become annotating(article, fileSizeLimit, remaining - data.length, acc ++ data)
		case PeerClosed ⇒ cancel(article)
	}

	def writing(article: ArticleRequest, fileSizeLimit: Long, remaining: Long, acc: ByteString): Receive = {
		case Received(data) if remaining <= data.length ⇒ //Text completed
			log.info("text completed")
			context become publishing(article.write((acc ++ data.take(Math.min(remaining, Integer.MAX_VALUE).toInt)).utf8String.trim()), fileSizeLimit, ByteString.empty)
			_success("")
		case Received(data) if remaining > data.length ⇒ context become writing(article, fileSizeLimit, remaining - data.length, acc ++ data)
		case PeerClosed ⇒ cancel(article)
	}

	//needs to be asyncronous since writing to disk is slow
	def storing(article: ArticleRequest, fileSizeLimit: Long, sort: String, remaining: Long, file: Attachment, channel: FileChannel): Receive = {
		case Received(data) ⇒
			//data.length has type Int so it will never be more than the length of the segment returned by data.take()
			val limit = Math.min(remaining, Integer.MAX_VALUE).toInt
			val completed = remaining <= data.length
			packets offer WriteRequest(data.take(limit), channel, completed, file.file)
			if (completed) { //File contents fully received
				context become publishing(article.attach(sort, file), fileSizeLimit, data.drop(limit))
				log.info("stored file")
			} else
				context become storing(article, fileSizeLimit, sort, remaining - data.length, file, channel)
		case PeerClosed ⇒ cancel(article)
	}
}
