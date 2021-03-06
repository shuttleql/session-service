package com.shuttleql.services.session

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.{Region, Regions}
import com.amazonaws.services.sns.AmazonSNSClient
import com.amazonaws.services.sns.model.PublishRequest
import com.shuttleql.services.session.tables.{UserToSession, UserToSessions}
import com.typesafe.config.ConfigFactory
import slick.lifted.TableQuery
import slick.driver.PostgresDriver.api._

import scala.concurrent.duration.Duration
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

object UserToSessionsDAO extends TableQuery(new UserToSessions(_)) {
  val conf = ConfigFactory.load()

  val creds = new BasicAWSCredentials(conf.getString("amazon.access_key"), conf.getString("amazon.secret_key"))
  val snsClient = new AmazonSNSClient(creds)
  snsClient.setRegion(Region.getRegion(Regions.US_WEST_2))

  def broadcastUserUpdate(): Unit = {
    val publishReq = new PublishRequest()
      .withTopicArn(conf.getString("amazon.topic_arn"))
      .withSubject("update")
      .withMessage("{ \"resource\": \"users\" }")

    snsClient.publish(publishReq)
  }

  def initDb() = {
    Database.forConfig("db")
  }

  def setupTables(): Option[Unit] = {
    val db = initDb

    try {
      Option(Await.result(db.run(this.schema.create), Duration.Inf))
    } catch {
      case e: Exception => None
    } finally {
      db.close
    }
  }

  def create(userId: Long, sessionId: Long): Option[UserToSession] = {
    val db = initDb
    val now = new java.sql.Timestamp(System.currentTimeMillis())
    val newUserToSession = UserToSession(userId, sessionId, now, Some(now))

    try {
      val dbAction = (
        for {
          // Delete any existing userToSession
          _ <- this.filter(_.sessionId === sessionId).filter(_.userId === userId).delete
          newRow <- this returning this += newUserToSession
        } yield newRow
      ).transactionally

      val result: UserToSession = Await.result(db.run(dbAction), Duration.Inf)
      Option(result)
    } catch {
      case e: Exception => None
    } finally {
      broadcastUserUpdate
      db.close
    }
  }

  def update(userId: Long, sessionId: Long): Option[UserToSession] = {
    val db = initDb
    val now = new java.sql.Timestamp(System.currentTimeMillis())

    try {
      val userToSession = Await.result(db.run(this.filter(_.sessionId === sessionId)
            .filter(_.userId === userId).result).map(_.headOption), Duration.Inf)

      Await.result(db.run(this.filter(_.sessionId === sessionId)
            .filter(_.userId === userId)
            .map(x => (x.checkedOutAt))
            .update(now)), Duration.Inf)

      Option(userToSession.get)
    } catch {
      case e: Exception => None
    } finally {
      broadcastUserUpdate
      db.close
    }
  }

  def findUsersBySession(sessionId: Long): Option[Seq[UserToSession]] = {
    val db = initDb

    try {
      val result: Seq[UserToSession] = Await.result(db.run(this.filter(_.sessionId === sessionId)
        .filter(x => x.checkedInAt === x.checkedOutAt).result), Duration.Inf)
      Option(result)
    } catch {
      case e: Exception => None
    } finally {
      db.close
    }
  }
}
