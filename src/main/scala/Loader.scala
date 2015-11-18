import java.io._

trait Loader {
  @throws(classOf[IOException]) def read(fileIn: InputStream): Stream[(Int, scala.Array[Byte])] = {
    val bytes = scala.Array.fill[Byte](1024)(0)
    val length = fileIn.read(bytes)
    (length, bytes) #:: read(fileIn)
  }
}

