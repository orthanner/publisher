import java.io.File
import scala.collection._

case class Attachment(name: String, var originalName: String, length: Long, file: File, var id: Option[Long] = None, var deleted: Boolean = false, var renamed: Boolean = false)
case class ArticleRequest(var id: Option[Int], token: String, global: Boolean, local: Boolean, gallery: Boolean, author: String, author_unit: String, title: String, var date: String = "", val annotation: String = "", val text: String = "", val uuid: String = java.util.UUID.randomUUID().toString(), val logo: Option[Attachment] = None, val files: List[Attachment] = List.empty, images: List[Attachment] = List.empty, var oldLogos: List[Attachment] = List.empty) {

	def annotate(anno: String) = ArticleRequest(id, token, global, local, gallery, author, author_unit, title, date, anno, text, uuid, logo, files, images)

	def write(content: String) = ArticleRequest(id, token, global, local, gallery, author, author_unit, title, date, annotation, content, uuid, logo, files, images)

	def setLogo(l: Option[Attachment]) = ArticleRequest(id, token, global, local, gallery, author, author_unit, title, date, annotation, text, uuid, l, files, images, logo.toList ++ oldLogos)

	def attachFile(f: Attachment) = ArticleRequest(id, token, global, local, gallery, author, author_unit, title, date, annotation, text, uuid, logo, f :: files, images)

	def attachFiles(fs: Seq[Attachment]) = ArticleRequest(id, token, global, local, gallery, author, author_unit, title, date, annotation, text, uuid, logo, files ++ fs, images)

	def attachImage(f: Attachment) = ArticleRequest(id, token, global, local, gallery, author, author_unit, title, date, annotation, text, uuid, logo, files, f :: images)

	def attachImages(fs: Seq[Attachment]) = ArticleRequest(id, token, global, local, gallery, author, author_unit, title, date, annotation, text, uuid, logo, files, images ++ fs)

	def attach(sort: String, file: Attachment) = sort match {
		case "file" ⇒ attachFile(file)
		case "image" ⇒ attachImage(file)
		case "logo" ⇒ setLogo(Option(file))
	}

	def clearAttachments = ArticleRequest(id, token, global, local, gallery, author, author_unit, title, date, annotation, text, uuid)

	lazy val link = "<a href=\"feed_user.do?id=%s&amp;offset=0\">%s</a>".format(author, author_unit)
}
