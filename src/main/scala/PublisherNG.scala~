import language.postfixOps
import akka.actor._
import akka.io._
import akka.util.ByteString
import java.net.InetSocketAddress
import org.apache.commons.dbcp2._
import akka.util._
import com.typesafe.config._
import java.io.File
import org.springframework.jdbc.core._

class Publisher(args: Array[String]) extends Actor with ActorLogging {
	import Tcp._
	import context.system

	lazy val config = ConfigFactory.parseFile(new File(if (args.length > 0) args(0) else "publisher.conf"))
		.withFallback(ConfigFactory.parseString("tcp.port=9877"))
		.withFallback(ConfigFactory.parseString("auth.host=127.0.0.1"))
		.withFallback(ConfigFactory.parseString("auth.port=9876"))
		.withFallback(ConfigFactory.parseString("ssl.algorithm=RSA"))
		.withFallback(ConfigFactory.parseString("ssl.streamCipher=AES"))
		.withFallback(ConfigFactory.parseString("ssl.cipher=RSA/ECB/OAEPWithSHA-256AndMGF1Padding"))
		.withFallback(ConfigFactory.parseString("ssl.signature=SHA512withRSA"))
		.withFallback(ConfigFactory.parseString("jdbc.connLimit=4"))
		.withFallback(ConfigFactory.parseString("files.maxSize=5m"))

	lazy val DB = {
		val db = new BasicDataSource()
		db setDriverClassName config.getString("jdbc.driver")
		db setUrl config.getString("jdbc.url")

		db setUsername config.getString("jdbc.username")
		db setPassword config.getString("jdbc.password")
		db setMaxTotal config.getInt("jdbc.connLimit")
		new JdbcTemplate(db)
	}

	override def preStart = {
		IO(Tcp) ! Bind(self, new InetSocketAddress(config.getInt("tcp.port")))
	}

	def receive = {
		case b @ Bound(localAddress) ⇒ context become listening(localAddress, sender)
		case CommandFailed(_: Bind) ⇒
			log.error("Could not bind")
			context stop self
	}

	def listening(addr: InetSocketAddress, manager: ActorRef): Receive = {
		case c @ Connected(remote, local) ⇒
			log.info("connected client: {}", remote)
			sender() ! Register(context.actorOf(Props(classOf[RequestHandler], sender(), DB, remote, config)))
		case Unbind ⇒ manager ! Unbind
		case Unbound ⇒ context unbecome
	}

}
