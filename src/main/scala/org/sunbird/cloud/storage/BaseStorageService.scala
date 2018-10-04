package org.sunbird.cloud.storage

import org.jclouds.blobstore._
import com.google.common.io._
import java.io._

import org.apache.commons.io.FilenameUtils
import org.apache.tika.Tika
import org.sunbird.cloud.storage.exception.StorageServiceException
import org.sunbird.cloud.storage.util.{CommonUtil, JSONUtils}

import collection.JavaConverters._
import org.jclouds.blobstore.options.ListContainerOptions.Builder.prefix
import org.sunbird.cloud.storage.Model.Blob
import org.jclouds.blobstore.options.CopyOptions
import org.jclouds.blobstore.options.PutOptions.Builder.multipart
import org.sunbird.cloud.storage.conf.AppConf

trait BaseStorageService extends IStorageService {

    var context: BlobStoreContext
    var blobStore: BlobStore
    var maxRetries: Int = 1
    var maxSignedurlTTL: Int = 604800
    var attempt = 0
    var maxContentLength = 0
    val tika = new Tika()

    override def upload(container: String, file: String, objectKey: String, isPublic: Boolean = false,
                        isDirectory: Boolean = false, ttl: Option[Int] = None,
                        retryCount: Option[Int] = None): String = {

        try {
            if(isDirectory) {
                val d = new File(file)
                val files = if (d.exists && d.isDirectory) {
                    d.listFiles.filter(_.isFile).toList
                } else {
                    List[File]()
                }
                val list = files.map {f =>
                    val key = objectKey + f.getName.split("/").last
                    upload(container, f.getAbsolutePath, key)
                }
                list.toString()
            } else {
                if (attempt == retryCount.getOrElse(maxRetries)) {
                    val message = s"Failed to upload. file: $file, key: $objectKey, attempt: $attempt, " +
                      s"maxAttempts: $retryCount. Exceeded maximum number of retries"
                    throw new StorageServiceException(message)
                }

                blobStore.createContainerInLocation(null, container)
                val fileObj = new File(file)
                val payload = Files.asByteSource(fileObj)
                val contentType = tika.detect(fileObj)
                val blob = blobStore.blobBuilder(objectKey).payload(payload)
                  .contentType(contentType).contentLength(payload.size()).build()
                blobStore.putBlob(container, blob, multipart())
                if (isPublic) {
                    getSignedURL(container, objectKey, Option(ttl.getOrElse(maxSignedurlTTL)))
                }
                else blobStore.getBlob(container, objectKey).getMetadata.getUri.toString
            }
        } catch {
            case e: Exception => {
                Thread.sleep(attempt*2000)
                attempt += 1
                upload(container, file, objectKey, isPublic, isDirectory, ttl, retryCount)
            }
        }
    }

    override def put(container: String, content: Array[Byte], objectKey: String,
                     isPublic: Option[Boolean] = Option(false), isDirectory: Option[Boolean] = Option(false),
                     ttl: Option[Int] = None, retryCount: Option[Int] = None): String = {

        try {

            if (attempt == retryCount.getOrElse(maxRetries)) {
                val message = s"Failed to upload. key: $objectKey, attempt: $attempt, " +
                  s"maxAttempts: $retryCount. Exceeded maximum number of retries"
                throw new StorageServiceException(message)
            }

            blobStore.createContainerInLocation(null, container)
            val blob = blobStore.blobBuilder(objectKey).payload(content).contentLength(content.length).build()
            blobStore.putBlob(container, blob)
            if(isPublic.get) {
                getSignedURL(container, objectKey, Option(ttl.getOrElse(maxSignedurlTTL)))
            }
            else blobStore.getBlob(container, objectKey).getMetadata.getUri.toString
        }
        catch {
            case e: Exception => {
                Thread.sleep(attempt*2000)
                attempt += 1
                put(container, content, objectKey, isPublic, isDirectory, ttl, retryCount)
            }
        }
    }

    override def getSignedURL(container: String, objectKey: String, ttl: Option[Int] = None,
                              permission: Option[String] = Option("r")): String = {
        if (permission.getOrElse("").equalsIgnoreCase("w")) {
            context.getSigner.signPutBlob(container, blobStore.blobBuilder(objectKey).forSigning().
              contentLength(maxContentLength).build(), 600l).getEndpoint.toString
        } else {
            context.getSigner.signGetBlob(container, objectKey, ttl.getOrElse(maxSignedurlTTL)).getEndpoint.toString
        }
    }

    override def download(container: String, objectKey: String, localPath: String, isDirectory: Boolean = false): Unit = {
        try {
            if(isDirectory) {
                val objects = listObjectKeys(container, objectKey, isDirectory)
                for (obj <- objects) {
                    val file = FilenameUtils.getName(obj)
                    val fileObj = blobStore.getBlob(container, obj)
                    val downloadPath = localPath + FilenameUtils.getPath(obj).split("/").last + "/"
                    CommonUtil.copyFile(fileObj.getPayload.getInput, downloadPath.replaceAll("//", "/"), file)
                }
            }
            else {
                val inStream = blobStore.getBlob(container, objectKey).getPayload.getInput
                val fileName = objectKey.split("/").last
                CommonUtil.copyFile(inStream, localPath, fileName)
            }
        } catch {
            case e: Exception =>
                throw new StorageServiceException(e.getMessage)
        }
    }

    override def deleteObject(container: String, objectKey: String, isDirectory: Option[Boolean] = Option(false)): Unit = {
        try {
            deleteObjects(container, List((objectKey, isDirectory.get)))
        } catch {
            case e: Exception =>
                throw new StorageServiceException(e.getMessage)
        }
    }

    override def deleteObjects(container: String, objectKeys: List[(String, Boolean)]): Unit = {
        try {
            for (obj <- objectKeys) {
                if(obj._2) {
                    val objList = blobStore.list(container, prefix(obj._1).recursive()).asScala.map(f => f.getName).toList
                    blobStore.removeBlobs(container, objList.asJavaCollection)
                }
                else {
                    blobStore.removeBlobs(container, List(obj._1).asJavaCollection)
                }
            }
        } catch {
            case e: Exception =>
                throw new StorageServiceException(e.getMessage)
        }
    }

    override def getObject(container: String, objectKey: String, withPayload: Option[Boolean] = Option(false)): Blob = {
        try {
            val blob = blobStore.getBlob(container, objectKey)
            val objData = blob.getMetadata
            val metaData = JSONUtils.deserialize[Map[String, AnyRef]](JSONUtils.serialize(objData))
            val payload = if(withPayload.get) Option(blob.getPayload.getContentMetadata.getContentMD5AsHashCode.asBytes()) else None
            Blob(objectKey, objData.getContentMetadata.getContentLength, objData.getLastModified, metaData, payload)
        } catch {
            case e: Exception =>
                throw new StorageServiceException(e.getMessage)
        }
    }

    override def listObjects(container: String, prefix: String, withPayload: Option[Boolean] = Option(false)): List[Blob] = {
        try {
            val objects = listObjectKeys(container, prefix)
            objects.map { obj =>
                getObject(container, obj, withPayload)
            }
        }
        catch {
            case e: Exception =>
                throw new StorageServiceException(e.getMessage)
        }
    }

    override def listObjectKeys(container: String, _prefix: String, isDirectory: Boolean = false): List[String] = {
        if(isDirectory)
            blobStore.list(container, prefix(_prefix).recursive()).asScala.map(f => f.getName).toList
        else
            blobStore.list(container, prefix(_prefix)).asScala.map(f => f.getName).toList
    }

    override def searchObjects(container: String, prefix: String, fromDate: Option[String] = None,
                               toDate: Option[String] = None, delta: Option[Int] = None,
                               pattern: String = "yyyy-MM-dd"): List[Blob] = {
        val from = if (delta.nonEmpty) CommonUtil.getStartDate(toDate, delta.get) else fromDate
        if (from.nonEmpty) {
            val dates = CommonUtil.getDatesBetween(from.get, toDate, pattern);
            val paths = for (date <- dates) yield {
                listObjects(container, prefix + date)
            }
            paths.flatMap { x => x.map { x => x } }.toList;
        } else {
            listObjects(container, prefix)
        }
    }

    override def searchObjectkeys(container: String, prefix: String, fromDate: Option[String] = None,
                                  toDate: Option[String] = None, delta: Option[Int] = None,
                                  pattern: String = "yyyy-MM-dd"): List[String] = {
        //        val objectList = searchObjects(container, prefix, fromDate, toDate, delta, pattern)
        //        getPaths(container, objectList);
        val from = if (delta.nonEmpty) CommonUtil.getStartDate(toDate, delta.get) else fromDate;
        if (from.nonEmpty) {
            val dates = CommonUtil.getDatesBetween(from.get, toDate, pattern);
            val paths = for (date <- dates) yield {
                listObjectKeys(container, prefix + date)
            }
            paths.flatMap { x => x.map { x => x } }.toList;
        } else {
            listObjectKeys(container, prefix)
        }
    }

    override def copyObjects(fromContainer: String, fromKey: String,
                             toContainer: String, toKey: String, isDirectory: Boolean = false): Unit = {
        if(isDirectory) {
            val updatedFromKey = if(fromKey.endsWith("/")) fromKey else fromKey+"/"
            val updatedToKey = if(toKey.endsWith("/")) toKey else toKey+"/"
            val objectKeys = listObjectKeys(fromContainer, updatedFromKey, isDirectory)
            for (obj <- objectKeys) {
                val objName = obj.replace(updatedFromKey, "")
                blobStore.copyBlob(fromContainer, obj, toContainer, updatedToKey+objName, CopyOptions.NONE)
            }
        }
        else blobStore.copyBlob(fromContainer, fromKey, toContainer, toKey, CopyOptions.NONE)
    }

    override def extractArchive(container: String, objectKey: String, toKey: String): Unit = {
        try {
            val localPath = AppConf.getConfig("local_extract_path")
            download(container, objectKey, localPath, isDirectory = false)
            val localFolder = localPath + "/" + toKey.split("/").last
            CommonUtil.unZip(localPath + "/" + objectKey.split("/").last, localFolder)
            upload(container, localFolder, toKey, isDirectory = true)
        }
        catch {
            case e: Exception =>
                throw new StorageServiceException(e.getMessage)
        }
    }

    override def getObjectData(container: String, objectKey: String): Array[String] = {

        try {
            val inStream = blobStore.getBlob(container, objectKey).getPayload.openStream()
            scala.io.Source.fromInputStream(inStream).getLines().toArray
        } catch {
            case e: Exception =>
                throw new StorageServiceException(e.getMessage)
        }
    }

    def closeContext() = {
        context.close()
    }

}
