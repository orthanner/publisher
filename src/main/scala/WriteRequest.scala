import java.nio._
import java.nio.channels._
import akka.actor._
import akka.io._
import akka.util._
import java.io._
import akka.event._

case class WriteRequest(data: ByteString, channel: FileChannel, close: Boolean, file: File) {
  import Tcp._

	@throws(classOf[IOException]) def write(log: LoggingAdapter): Boolean = {
		channel.write(data.asByteBuffer)
		if (close) {
			channel.force(true)
			channel.close()
		}
		close
	}
}
