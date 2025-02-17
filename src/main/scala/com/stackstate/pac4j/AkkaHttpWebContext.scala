package com.stackstate.pac4j

import java.util.{Optional, UUID}

import akka.http.scaladsl.model.HttpHeader.ParsingResult.{Error, Ok}
import akka.http.scaladsl.model.headers.HttpCookie
import akka.http.scaladsl.model.{ContentType, HttpHeader, HttpRequest}

import com.stackstate.pac4j.authorizer.CsrfCookieAuthorizer
import com.stackstate.pac4j.http.AkkaHttpSessionStore
import com.stackstate.pac4j.store.SessionStorage

import org.pac4j.core.context.{Cookie, WebContext}

import compat.java8.OptionConverters._
import scala.collection.JavaConverters._

/**
  * The AkkaHttpWebContext is responsible for wrapping an HTTP request
  * and stores changes that are produced by pac4j
  * and need to be applied to an HTTP response.
  */
class AkkaHttpWebContext(val request: HttpRequest,
                         val formFields: Seq[(String, String)],
                         private[pac4j] val sessionStorage: SessionStorage,
                         val sessionCookieName: String)
    extends WebContext {
  import com.stackstate.pac4j.AkkaHttpWebContext._

  private var changes = ResponseChanges.empty

  //Only compute the request cookies once
  private lazy val requestCookies = request.cookies.map { akkaCookie =>
    new Cookie(akkaCookie.name, akkaCookie.value)
  }.asJavaCollection

  //Request parameters are composed of form fields and the query part of the uri. Stored in a lazy val in order to only compute it once
  private lazy val requestParameters =
    formFields.toMap ++ request.getUri().query().toMap.asScala

  private def newSession() = {
    val sessionId = UUID.randomUUID().toString
    sessionStorage.createSessionIfNeeded(sessionId)
    sessionId
  }

  private[pac4j] var sessionId: String =
    request.cookies
      .filter(_.name == sessionCookieName) // TODO: collect
      .map(_.value)
      .find(session => sessionStorage.sessionExists(session))
      .getOrElse(newSession())

  private[pac4j] def destroySession() = {
    sessionStorage.destroySession(sessionId)
    sessionId = newSession()
    true
  }

  private[pac4j] def trackSession(session: String) = {
    sessionStorage.createSessionIfNeeded(session)
    sessionId = session
    true
  }

  override def getRequestCookies: java.util.Collection[Cookie] = requestCookies

  private def toAkkaHttpCookie(cookie: Cookie): HttpCookie = {
    HttpCookie(
      name = cookie.getName,
      value = cookie.getValue,
      expires = None,
      maxAge = if (cookie.getMaxAge < 0) None else Some(cookie.getMaxAge.toLong),
      domain = Option(cookie.getDomain),
      path = Option(cookie.getPath),
      secure = cookie.isSecure,
      httpOnly = cookie.isHttpOnly,
      extension = None
    )
  }

  override def addResponseCookie(cookie: Cookie): Unit = {
    val httpCookie = toAkkaHttpCookie(cookie)
    changes = changes.copy(cookies = changes.cookies ++ List(httpCookie))
  }

  override lazy val getSessionStore = new AkkaHttpSessionStore()

  override def getRemoteAddr: String = {
    request.getUri().getHost.address()
  }

  override def setResponseHeader(name: String, value: String): Unit = {
    val header = HttpHeader.parse(name, value) match {
      case Ok(h, _) => h
      case Error(error) => throw new IllegalArgumentException(s"Error parsing http header ${error.formatPretty}")
    }

    // Avoid adding duplicate headers, Pac4J expects to overwrite headers like `Location`
    changes = changes.copy(headers = header :: changes.headers.filter(_.name != name))
  }

  @com.github.ghik.silencer.silent("mapValues")
  override def getRequestParameters: java.util.Map[String, Array[String]] =
    requestParameters.mapValues(Array(_)).toMap.asJava

  override def getFullRequestURL: String = {
    request.getUri().toString
  }

  override def getServerName: String = {
    request.getUri().host.address().split(":")(0)
  }

  override def setResponseContentType(contentType: String): Unit = {
    ContentType.parse(contentType) match {
      case Right(ct) =>
        changes = changes.copy(contentType = Some(ct))

      case Left(_) =>
        throw new IllegalArgumentException(s"Invalid response content type ${contentType}")
    }
  }

  override def getPath: String = {
    request.getUri().path
  }

  override def getRequestParameter(name: String): Optional[String] = {
    requestParameters.get(name).asJava
  }

  override def getRequestHeader(name: String): Optional[String] = {
    request.headers.find(_.name().toLowerCase() == name.toLowerCase).map(_.value).asJava
  }

  lazy val getScheme: String = request.getUri().getScheme

  def isSecure: Boolean = getScheme.toLowerCase == "https"

  override def getRequestMethod: String = request.method.value

  override def getServerPort: Int = request.getUri().getPort

  override def setRequestAttribute(name: String, value: scala.AnyRef): Unit =
    changes = changes.copy(attributes = changes.attributes ++ Map[String, AnyRef](name -> value))

  override def getRequestAttribute(name: String): Optional[AnyRef] =
    changes.attributes.get(name).asJava

  def getContentType: Option[ContentType] = changes.contentType

  def getChanges: ResponseChanges = changes

  def addResponseSessionCookie(): Unit = {
    val cookie = new Cookie(sessionCookieName, sessionId)

    cookie.setSecure(isSecure)
    cookie.setMaxAge(sessionStorage.sessionLifetime.toSeconds.toInt)
    cookie.setHttpOnly(true)
    cookie.setPath("/")

    addResponseCookie(cookie)
  }

  def sessionCookieIsValid(): Boolean = {
    val cookie = request.cookies.find(_.name == sessionCookieName)
    cookie.exists(c => sessionStorage.sessionExists(c.value))
  }

  def addResponseCsrfCookie(): Unit = {
    CsrfCookieAuthorizer(this, Some(sessionStorage.sessionLifetime))
    ()
  }
}

object AkkaHttpWebContext {
  def apply(request: HttpRequest, formFields: Seq[(String, String)], sessionStorage: SessionStorage, sessionCookieName: String): AkkaHttpWebContext =
    new AkkaHttpWebContext(request, formFields, sessionStorage, sessionCookieName)

  //This class is where all the HTTP response changes are stored so that they can later be applied to an HTTP Request
  case class ResponseChanges private (val headers: List[HttpHeader],
                                      val contentType: Option[ContentType],
                                      val content: String,
                                      val cookies: List[HttpCookie],
                                      val attributes: Map[String, AnyRef])

  object ResponseChanges {
    def empty: ResponseChanges = {
      ResponseChanges(List.empty, None, "", List.empty, Map.empty)
    }
  }

  private[pac4j] val DEFAULT_COOKIE_NAME = "AkkaHttpPac4jSession"
}
