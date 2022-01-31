package org.orbeon.oxf.util

import org.apache.commons.fileupload.FileItem
import org.apache.commons.fileupload.disk.{DiskFileItem, DiskFileItemFactory}
import org.orbeon.io.IOUtils._
import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.externalcontext.ExternalContext
import org.orbeon.oxf.pipeline.api.PipelineContext
import org.orbeon.oxf.util.NetUtils._
import org.slf4j.Logger

import java.io.InputStream


object FileItemSupport {

  import Private._

  def inputStreamToAnyURI(inputStream: InputStream, scope: Int, logger: Logger): (String, Long) = {

    val (fileItem, size) = prepareFileItemFromInputStream(inputStream, scope, logger)

    val storeLocation = fileItem.asInstanceOf[DiskFileItem].getStoreLocation

    // Escape "+" because at least in one environment (JBoss 5.1.0 GA on OS X) not escaping the "+" in a file URL causes
    // later incorrect conversion to space.
    // 2022-02-18: Not sure if this is still relevant or needed.
    (storeLocation.toURI.toString.replace("+", "%2B"), size)
  }

  def prepareFileItem(scope: Int, logger: Logger): FileItem = {

    val fileItem = fileItemFactory.createItem("dummy", "dummy", false, null)

    scope match {
      case REQUEST_SCOPE     => deleteFileOnRequestEnd(fileItem, logger)
      case SESSION_SCOPE     => deleteFileOnSessionTermination(fileItem, logger)
      case APPLICATION_SCOPE => deleteFileOnApplicationDestroyed(fileItem, logger)
      case _                 => throw new OXFException(s"Invalid scope requested: $scope")
    }

    fileItem
  }

  private object Private {

    lazy val fileItemFactory = new DiskFileItemFactory(0, NetUtils.getTemporaryDirectory)

    def prepareFileItemFromInputStream(inputStream: InputStream, scope: Int, logger: Logger): (FileItem, Long) = {

      val fileItem = prepareFileItem(scope, logger)

      var sizeAccumulator = 0L

      copyStreamAndClose(inputStream, fileItem.getOutputStream, read => sizeAccumulator += read, doCloseOut = true)

      fileItem.asInstanceOf[DiskFileItem].getStoreLocation.createNewFile()

      (fileItem, sizeAccumulator)
    }

    def deleteFileOnRequestEnd(fileItem: FileItem, logger: Logger): Unit =
      PipelineContext.get.addContextListener((_: Boolean) => deleteFileItem(fileItem, REQUEST_SCOPE, logger))

    def deleteFileOnSessionTermination(fileItem: FileItem, logger: Logger): Unit =
      getExternalContext.getSessionOpt(false) match {
        case Some(session) =>
          try
            session.addListener((_: ExternalContext.Session) => deleteFileItem(fileItem, SESSION_SCOPE, logger))
          catch {
            case e: IllegalStateException =>
              if (logger ne null)
                logger.info(s"Unable to add session listener: ${e.getMessage}")
              deleteFileItem(fileItem, SESSION_SCOPE, logger) // remove immediately
              throw e
          }
        case None =>
          if (logger ne null)
            logger.debug(s"No existing session found so cannot register temporary file deletion upon session destruction: `${fileItem.getName}`")
      }

    def deleteFileOnApplicationDestroyed(fileItem: FileItem, logger: Logger): Unit =
      getExternalContext.getWebAppContext.addListener(() => deleteFileItem(fileItem, APPLICATION_SCOPE, logger))

    def deleteFileItem(fileItem: FileItem, scope: Int, logger: Logger): Unit = {
      fileItem.delete()
      if ((logger ne null) && logger.isDebugEnabled)
        fileItem match {
          case diskFileItem: DiskFileItem =>
            val storeLocation = diskFileItem.getStoreLocation
            if (storeLocation ne null) {

              val scopeString =
                scope match {
                  case REQUEST_SCOPE     => "request"
                  case SESSION_SCOPE     => "session"
                  case APPLICATION_SCOPE => "application"
                  case _                 => throw new OXFException(s"Invalid scope requested: $scope")
                }

              val absolutePath =
                storeLocation.getAbsolutePath

              logger.debug(s"deleting temporary $scopeString-scoped file upon session destruction: `$absolutePath`")
            }
          case _ =>
        }
    }
  }
}