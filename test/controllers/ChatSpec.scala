package controllers

import akka.actor.ActorSystem
import org.specs2.mock.Mockito
import play.api.i18n.MessagesApi
import play.api.test._

class ChatSpec extends PlaySpecification with Mockito {

  val mockMessagesApi = mock[MessagesApi]
  val mockActorSystem = mock[ActorSystem]
  val app = new Chat(mockMessagesApi, mockActorSystem)
  val additionalConf = Map("play.crypto.secret" -> "_Zgs2h=lF1BuKGAUNb5bsL<nQ62H=4xWYlcOT;NEmepkbjdb9PFl;hJ0ZzG/YkD=")

  "Chat" should {
    "redirect to index during leaving" in {
      val leave = app.leave()(FakeRequest())
      status(leave) must equalTo(SEE_OTHER)
      redirectLocation(leave) must beSome.which(_ == "/")
    }

    "redirect to chat after defining nickname" in new WithApplication(app = FakeApplication(additionalConfiguration = additionalConf)) {
      val Mickey = "Mickey Mouse"
      val nickname = route(FakeRequest(POST, "/nickname").withFormUrlEncodedBody("nickname" -> Mickey)).get
      status(nickname) must equalTo(SEE_OTHER)
      redirectLocation(nickname) must beSome.which(_ == "/chat")
      session(nickname).get("user") must beSome(Mickey)
    }

    "bad request for empty nickname" in new WithApplication(app = FakeApplication(additionalConfiguration = additionalConf)) {
      val result = route(FakeRequest(POST, "/nickname").withFormUrlEncodedBody("nickname" -> "")).get
      status(result) must equalTo(BAD_REQUEST)
    }

    "redirect to index instead chat if session is empty" in {
      val chat = app.chat()(FakeRequest())
      status(chat) must equalTo(SEE_OTHER)
      redirectLocation(chat) must beSome.which(_ == "/")
    }

    "open chat room" in new WithApplication(app = FakeApplication(additionalConfiguration = additionalConf)) {
      val john = "johnsmith"
      val chat = route(FakeRequest(GET, "/chat").withSession("user" -> john)).get
      status(chat) must equalTo(OK)
      contentAsString(chat) must contain(s"User : $john")
    }
  }
}
