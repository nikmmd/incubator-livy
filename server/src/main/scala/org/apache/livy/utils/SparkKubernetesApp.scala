/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.livy.utils

import java.lang
import java.util.UUID
import java.util.concurrent.TimeoutException

import io.fabric8.kubernetes.api.model.{ConfigBuilder ⇒ _, _}
import io.fabric8.kubernetes.client._
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable
import org.apache.livy.{LivyConf, Logging, Utils}
import org.joda.time.{DateTime, DateTimeZone}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

object SparkKubernetesApp extends Logging {

  import KubernetesExtensions._
  import KubernetesUtils._

  def init(livyConf: LivyConf): Unit = {
    cacheLogSize = livyConf.getInt(LivyConf.SPARK_LOGS_SIZE)
    sparkNamespacePrefix = livyConf.get(LivyConf.KUBERNETES_SPARK_NAMESPACE_PREFIX)
    gcTtl = livyConf.getTimeAsMs(LivyConf.KUBERNETES_GC_TTL).milliseconds
    gcCheckTimeout = livyConf.getTimeAsMs(LivyConf.KUBERNETES_GC_CHECK_TIMEOUT).milliseconds
    logger.info(
      s"Initialized SparkKubernetesApp: " +
        s"master=[${livyConf.sparkMaster()}] " +
        s"| gcTtl=[$gcTtl] " +
        s"| gcCheckInterval=[$gcCheckTimeout]"
    )
    kubernetesNamespaceGcThread.setDaemon(true)
    kubernetesNamespaceGcThread.setName("kubernetesGcThread")
    kubernetesNamespaceGcThread.start()
  }

  private var cacheLogSize        : Int            = _
  private var sparkNamespacePrefix: String         = _
  private var gcTtl               : FiniteDuration = _
  private var gcCheckTimeout      : FiniteDuration = _

  // KubernetesClient is thread safe. Create once, share it across threads.
  lazy val kubernetesClient: DefaultKubernetesClient = {
    val config = new ConfigBuilder()
      .withApiVersion("v1")
      // TODO add config options to deploy Livy not inside Kubernetes cluster
      //      .withMasterUrl("https://kubernetes.default.svc")
      //      .withOauthToken(Files.toString(new File(KUBERNETES_SERVICE_ACCOUNT_TOKEN_PATH), Charsets.UTF_8))
      //      .withCaCertFile(KUBERNETES_SERVICE_ACCOUNT_CA_CRT_PATH)
      .build()
    new DefaultKubernetesClient(config)
  }

  private def getKubernetesTagToAppIdTimeout(livyConf: LivyConf): FiniteDuration =
    livyConf.getTimeAsMs(LivyConf.KUBERNETES_APP_LOOKUP_TIMEOUT).milliseconds

  private def getKubernetesPollInterval(livyConf: LivyConf): FiniteDuration =
    livyConf.getTimeAsMs(LivyConf.KUBERNETES_POLL_INTERVAL).milliseconds

  private val kubernetesNamespaceGcThread = new Thread() {
    override def run(): Unit = {
      while (true) {
        val sparkNamespaces = kubernetesClient.getNamespacesWithPrefix(sparkNamespacePrefix)

        val sparkNamespacesWithoutDrivers = sparkNamespaces.filterNot(ns ⇒
          kubernetesClient.pods.inNamespace(ns.getMetadata.getName).list.getItems.asScala.exists(isSparkDriver)
        )
        val sparkNamespacesWithDrivers = sparkNamespaces -- sparkNamespacesWithoutDrivers

        val sparkNamespacesToDelete =
          sparkNamespacesWithoutDrivers.filter(isExpired(_, gcTtl)) ++
            sparkNamespacesWithDrivers
              .filter(ns ⇒ kubernetesClient.pods.inNamespace(ns.getMetadata.getName).list.getItems.asScala
                .filter(isSparkDriver)
                .forall(isSparkDriverExpired(_, gcTtl))
              )
        if (sparkNamespacesToDelete.nonEmpty) {
          info(s"GC outdated apps: ${sparkNamespacesToDelete.map(_.getMetadata.getName).mkString(", ")}")
          kubernetesClient.namespaces.delete(sparkNamespacesToDelete: _*)
        }
        Clock.sleep(gcCheckTimeout.toMillis)
      }
    }
  }
}

class SparkKubernetesApp private[utils](
    appTag: String,
    appIdOption: Option[String],
    process: Option[LineBufferedProcess],
    listener: Option[SparkAppListener],
    livyConf: LivyConf,
    kubernetesClient: => KubernetesClient = SparkKubernetesApp.kubernetesClient) // For unit test. TODO ???
  extends SparkApp
    with Logging {

  import KubernetesConstants._
  import KubernetesExtensions._
  import KubernetesUtils._
  import SparkApp.State._
  import SparkKubernetesApp._

  private        val appIdPromise         : Promise[String]           = Promise()
  private[utils] var state                : SparkApp.State            = SparkApp.State.STARTING
  private        val runningStates        : Seq[SparkApp.State.Value] = Seq(STARTING, RUNNING)
  private        var kubernetesDiagnostics: IndexedSeq[String]        = IndexedSeq.empty[String]
  private        var kubernetesAppLog     : IndexedSeq[String]        = IndexedSeq.empty[String]

  // TODO cache log $logSize, watch and persist full log
  override def log(): IndexedSeq[String] =
    ("stdout: " +: kubernetesAppLog) ++
      ("\nstderr: " +: (process.map(_.inputLines).getOrElse(ArrayBuffer.empty[String]) ++ process.map(_.errorLines).getOrElse(ArrayBuffer.empty[String]))) ++
      ("\nKubernetes Diagnostics: " +: kubernetesDiagnostics)

  override def kill(): Unit = synchronized {
    if (isRunning) {
      try {
        // TODO flush log and collect all the diagnostics information
        kubernetesClient.deleteNamespace(appTag)
      } catch {
        // TODO analyse possible exceptions and handle them
        case _: TimeoutException | _: InterruptedException =>
          warn("Deleting a session while its Kubernetes application is not found.")
          kubernetesAppMonitorThread.interrupt()
      } finally {
        // TODO persist collected data
        process.foreach(_.destroy())
      }
    }
  }

  private def changeState(newState: SparkApp.State.Value): Unit = {
    if (state != newState) {
      listener.foreach(_.stateChanged(state, newState))
      state = newState
    }
  }

  private def getAppIdFromTag(
      appTag: String,
      pollInterval: Duration,
      deadline: Deadline): Option[String] = {
    val driver = kubernetesClient.getSparkDriverByAppTag(appTag)
    if (driver.isDefined) {
      Option(driver.get.getMetadata.getLabels.get(KUBERNETES_SPARK_APP_ID_LABEL))
    } else {
      if (deadline.isOverdue) {
        kill()
        throw new Exception(s"No Kubernetes application is found with tag $appTag in " +
          getKubernetesTagToAppIdTimeout(livyConf) / 1000 + " seconds. " +
          "Please check your cluster status, it is may be very busy.")
      } else {
        Clock.sleep(pollInterval.toMillis)
        getAppIdFromTag(appTag, pollInterval, deadline)
      }
    }
  }

  private def isRunning: Boolean = runningStates.contains(state)

  // Exposed for unit test.
  private[utils] def mapKubernetesState(kubernetesPodStatus: Option[PodStatus]): SparkApp.State.Value = {
    val state = Try(kubernetesPodStatus.get.getPhase.toLowerCase).getOrElse("error")
    state match {
      case "pending" | "containercreating" => SparkApp.State.STARTING
      case "running"                       => SparkApp.State.RUNNING
      case "completed"                     => SparkApp.State.FINISHED
      case "failed" | "error"              => SparkApp.State.FAILED
      case _                               => SparkApp.State.KILLED
    }
  }

  // Exposed for unit test.
  // TODO Instead of spawning a thread for every session, create a centralized thread and
  // batch Kubernetes queries.
  private[utils] val kubernetesAppMonitorThread = Utils.startDaemonThread(s"kubernetesAppMonitorThread-$this") {
    try {
      // If appId is not known, query Kubernetes by appTag to get it.
      val appId = try {
        appIdOption.getOrElse {
          val pollInterval = getKubernetesPollInterval(livyConf)
          val deadline = getKubernetesTagToAppIdTimeout(livyConf).fromNow
          getAppIdFromTag(appTag, pollInterval, deadline).get
        }
      } catch {
        case e: Exception =>
          appIdPromise.failure(e)
          throw e
      }
      appIdPromise.success(appId)
      Thread.currentThread().setName(s"kubernetesAppMonitorThread-$appId")
      listener.foreach(_.appIdKnown(appId.toString))
      val pollInterval = getKubernetesPollInterval(livyConf)
      var appInfo = AppInfo()
      while (isRunning) {
        try {
          Clock.sleep(pollInterval.toMillis)
          // Refresh application state
          val sparkPods = kubernetesClient.getSparkPodsByAppTag(appTag)
          val driverPodOption = sparkPods.find(isSparkDriver)
          kubernetesDiagnostics = sparkPods
            .sortBy(_.getMetadata.getName)
            .map(buildSparkPodDiagnosticsPrettyString)
            .flatMap(_.split("\n"))
            .toIndexedSeq
          changeState(mapKubernetesState(Try(driverPodOption.get.getStatus).toOption))
          // Refresh app log cache
          kubernetesAppLog = kubernetesClient.getPodLog(driverPodOption.get, cacheLogSize)
          val latestAppInfo = {
            val historyServerOption = Option(livyConf.get(LivyConf.HISTORY_SERVER_URL))
            val historyServerInfo = if (historyServerOption.isDefined) Option(s"${historyServerOption.get}/history/$appId/jobs/") else None
            val driverMetadata = Try(driverPodOption.get.getMetadata)
            val sparkUiInfo = if (driverMetadata.isSuccess) {
              val meta = driverMetadata.get
              Option(s"${Option(livyConf.get(LivyConf.SERVER_PROXY_URL)).getOrElse("")}/${meta.getNamespace}/${meta.getName}-svc/jobs/")
            } else {
              None
            }
            AppInfo(sparkUiUrl = sparkUiInfo, historyServerUrl = historyServerInfo)
          }
          if (appInfo != latestAppInfo) {
            listener.foreach(_.infoChanged(latestAppInfo))
            appInfo = latestAppInfo
          }
        } catch {
          // This exception might be thrown during app is starting up. It's transient.
          case e: Throwable => throw e
        }
      }
    } catch {
      case _: InterruptedException =>
        kubernetesDiagnostics = ArrayBuffer("Session stopped by user.")
        changeState(SparkApp.State.KILLED)
      case e: Throwable            =>
        error(s"Error whiling refreshing Kubernetes state: $e")
        kubernetesDiagnostics = ArrayBuffer(e.toString +: e.getStackTrace.map(_.toString): _*)
        changeState(SparkApp.State.FAILED)
    }
  }

  def buildSparkPodDiagnosticsPrettyString(pod: Pod): String = {
    def printMap(map: mutable.Map[_, _]): String = map.map {
      case (key, value) ⇒ s"$key=$value"
    }.mkString(", ")

    s"${pod.getMetadata.getName}.${pod.getMetadata.getNamespace}:" +
      s"\n\tnode: ${pod.getSpec.getNodeName}" +
      s"\n\thostname: ${pod.getSpec.getHostname}" +
      s"\n\tpodIp: ${pod.getStatus.getPodIP}" +
      s"\n\tstartTime: ${pod.getStatus.getStartTime}" +
      s"\n\tphase: ${pod.getStatus.getPhase}" +
      s"\n\treason: ${pod.getStatus.getReason}" +
      s"\n\tmessage: ${pod.getStatus.getMessage}" +
      s"\n\tlabels: ${printMap(pod.getMetadata.getLabels.asScala)}" +
      s"\n\tcontainers:" +
      s"\n\t\t${
        pod.getSpec.getContainers.asScala.map(container ⇒
          s"${container.getName}:" +
            s"\n\t\t\timage: ${container.getImage}" +
            s"\n\t\t\trequests: ${printMap(container.getResources.getRequests.asScala)}" +
            s"\n\t\t\tlimits: ${printMap(container.getResources.getLimits.asScala)}" +
            s"\n\t\t\tcommand: ${container.getCommand} ${container.getArgs}"
        ).mkString("\n\t\t")
      }" +
      s"\n\tconditions:" +
      s"\n\t\t${pod.getStatus.getConditions.asScala.mkString("\n\t\t")}"
  }

}

object KubernetesExtensions {

  import KubernetesConstants._

  implicit class KubernetesClientExtensions(client: KubernetesClient) {
    def selectSparkDrivers(sparkRoleLabel: String = KUBERNETES_SPARK_ROLE_LABEL, sparkRoleDriver: String = KUBERNETES_SPARK_ROLE_DRIVER): FilterWatchListDeletable[Pod, PodList, lang.Boolean, Watch, Watcher[Pod]] =
      client.pods.inAnyNamespace.withLabel(sparkRoleLabel, sparkRoleDriver)

    def getSparkDriverByAppId(appId: String, appIdLabel: String = KUBERNETES_SPARK_APP_ID_LABEL): Option[Pod] =
      Try(selectSparkDrivers().withLabel(appIdLabel, appId).list.getItems.asScala.head).toOption

    def getSparkDriverByAppTag(appTag: String, appTagLabel: String = KUBERNETES_SPARK_APP_TAG_LABEL): Option[Pod] =
      Try(selectSparkDrivers().withLabel(appTagLabel, appTag).list.getItems.asScala.head).toOption

    def getSparkPodsByAppId(appId: String, sparkAppIdLabel: String = KUBERNETES_SPARK_APP_ID_LABEL): mutable.Buffer[Pod] =
      client.pods.inAnyNamespace.withLabel(sparkAppIdLabel, appId).list().getItems.asScala

    def getSparkPodsByAppTag(appTag: String, sparkAppTagLabel: String = KUBERNETES_SPARK_APP_TAG_LABEL): mutable.Buffer[Pod] =
      client.pods.inAnyNamespace.withLabel(sparkAppTagLabel, appTag).list().getItems.asScala

    def getNamespacesWithPrefix(prefix: String): mutable.Buffer[Namespace] =
      client.namespaces().list().getItems.asScala.filter(_.getMetadata.getName.startsWith(prefix))

    def createNamespace(name: String): Namespace = client.namespaces.create(buildNamespace(name))

    def deleteNamespace(name: String): Boolean = client.namespaces.delete(buildNamespace(name))

    def buildNamespace(name: String): Namespace = new NamespaceBuilder().withNewMetadata.withName(name).endMetadata.build()

    def createImagePullSecret(namespace: String, name: String, secret: String): Secret = client.secrets.createNew
      .withNewMetadata().withName(name).withNamespace(namespace).endMetadata
      .withType(KUBERNETES_IMAGE_PULL_SECRET_TYPE)
      .addToData(KUBERNETES_IMAGE_PULL_SECRET_DATA_KEY, secret)
      .done()

    def getPodLog(pod: Pod, cacheLogSize: Int): IndexedSeq[String] = Try({
      val name = pod.getMetadata.getName
      val namespace = pod.getMetadata.getNamespace
      client.pods.inNamespace(namespace).withName(name)
        .tailingLines(cacheLogSize).getLog.split("\n").toIndexedSeq
    }).getOrElse(IndexedSeq("No log..."))
  }

}

object KubernetesConstants {
  val KUBERNETES_SPARK_APP_ID_LABEL  = "spark-app-selector"
  val KUBERNETES_SPARK_APP_TAG_LABEL = "spark-app-tag"
  val KUBERNETES_SPARK_ROLE_LABEL    = "spark-role"
  val KUBERNETES_SPARK_ROLE_DRIVER   = "driver"

  val KUBERNETES_IMAGE_PULL_SECRET_TYPE     = "kubernetes.io/dockerconfigjson"
  val KUBERNETES_IMAGE_PULL_SECRET_DATA_KEY = ".dockerconfigjson"
}

object KubernetesUtils {

  import KubernetesConstants._
  import org.apache.livy.server.batch.CreateBatchRequest

  def formatAppId(appId: String): String = {
    val formatted = s"stub.$appId".split("\\.").last.toLowerCase().replaceAll("[^0-9a-z]", "")
    val shortened = if (formatted.length > 32) formatted.substring(0, 32) else formatted
    s"$shortened-${System.currentTimeMillis()}"
  }

  def getAppId(appId: Option[String], appName: Option[String], className: Option[String]): String = {
    if (appId.isDefined) {
      appId.get
    } else if (appName.isDefined) {
      formatAppId(appName.get)
    } else if (className.isDefined) {
      formatAppId(className.get)
    } else {
      s"spark-${UUID.randomUUID().toString.replaceAll("-", "")}"
    }
  }

  def prepareKubernetesNamespace(livyConf: LivyConf, namespace: String): Unit = {
    import KubernetesExtensions._
    import SparkKubernetesApp.kubernetesClient

    kubernetesClient.createNamespace(namespace)
    val imagePullSecretName = livyConf.get(LivyConf.KUBERNETES_IMAGE_PULL_SECRET_NAME)
    val imagePullSecretContent = livyConf.get(LivyConf.KUBERNETES_IMAGE_PULL_SECRET_CONTENT)
    if (imagePullSecretName != null && imagePullSecretName.nonEmpty &&
      imagePullSecretContent != null && imagePullSecretContent.nonEmpty) {
      kubernetesClient.inAnyNamespace.createImagePullSecret(namespace, imagePullSecretName, imagePullSecretContent)
    }
  }

  def prepareKubernetesSpecificConf(request: CreateBatchRequest, namespace: String): Map[String, String] = Map(
    "spark.app.id" -> getAppId(Try(request.conf("spark.app.id")).toOption, request.name, request.className),
    "spark.kubernetes.namespace" → namespace
  )

  def parseCreationTime(resource: HasMetadata): DateTime =
    org.joda.time.DateTime.parse(resource.getMetadata.getCreationTimestamp.getTime).withZone(DateTimeZone.UTC)

  def isExpired(resource: HasMetadata, ttl: FiniteDuration): Boolean =
    parseCreationTime(resource).plus(ttl.toMillis).isBefore(DateTime.now(DateTimeZone.UTC))

  def isSparkDriver(pod: Pod): Boolean = {
    pod.getMetadata.getLabels.containsKey(KUBERNETES_SPARK_ROLE_LABEL) &&
      pod.getMetadata.getLabels.get(KUBERNETES_SPARK_ROLE_LABEL) == KUBERNETES_SPARK_ROLE_DRIVER
  }

  def isSparkDriverExpired(driver: Pod, ttl: FiniteDuration): Boolean =
    isExpired(driver, ttl) && isSparkDriverFinished(driver.getStatus.getPhase)

  def isSparkDriverFinished(phase: String): Boolean =
    Try(Seq("error", "completed").contains(phase.toLowerCase) || phase.toLowerCase.contains("backoff")).getOrElse(false)

}