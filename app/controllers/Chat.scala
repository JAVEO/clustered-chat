package controllers

import javax.inject._

import actors.{ChatRoom, UserSocket}
import akka.actor._
import akka.stream.Materializer
import play.api.Play.current
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.json.JsValue
import play.api.libs.streams.ActorFlow
import play.api.mvc.{Action, Controller, WebSocket}

import scala.concurrent.Future

@Singleton
class Chat @Inject()(conf: play.api.Configuration, val messagesApi: MessagesApi, system: ActorSystem, mat: Materializer) extends Controller with I18nSupport {
  val User = "user"

  implicit val implicitMaterializer: Materializer = mat
  implicit val implicitActorSystem: ActorSystem = system

  val chatRoom = system.actorOf(Props[ChatRoom], "chat-room")

  val nickForm = Form(single("nickname" -> nonEmptyText))

  def index = Action { implicit request =>
    request.session.get(User).map { user =>
      Redirect(routes.Chat.chat()).flashing("info" -> s"Redirected to chat as $user user")
    }.getOrElse(Ok(views.html.index(nickForm)))
  }

  def nickname = Action { implicit request =>
    nickForm.bindFromRequest.fold(
      formWithErrors => {
        BadRequest(views.html.index(formWithErrors))
      },
      nickname => {
        Redirect(routes.Chat.chat())
          .withSession(request.session + (User -> nickname))
      }
    )
  }

  def leave = Action { implicit request =>
    Redirect(routes.Chat.index()).withNewSession.flashing("success" -> "See you soon!")
  }

  def chat = Action { implicit request =>
    request.session.get(User).map { user =>
      Ok(views.html.chat(user))
    }.getOrElse(Redirect(routes.Chat.index()))
  }

  def socket = WebSocket.acceptOrResult[JsValue, JsValue] { implicit request =>
    Future.successful(request.session.get(User) match {
      case None => Left(Forbidden)
      case Some(uid) =>
        Right(ActorFlow.actorRef(UserSocket.props(uid, conf)))
    })
  }
}
