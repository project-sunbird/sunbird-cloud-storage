package org.sunbird.cloud.storage.service

import org.sunbird.cloud.storage.factory.StorageConfig
import org.sunbird.cloud.storage.factory.StorageServiceFactory
import org.scalatest._
import org.sunbird.cloud.storage.conf.AppConf

class TestAzureStorageService extends FlatSpec with Matchers {

    ignore should "test for azure storage" in {

        val azureService = StorageServiceFactory.getStorageService(StorageConfig("azure", AppConf.getStorageKey("azure"), AppConf.getStorageSecret("azure")))

        val storageContainer = AppConf.getConfig("azure_storage_container")

        azureService.upload(storageContainer, "src/test/resources/test-data.log", "testUpload/test-blob.log")
        azureService.download(storageContainer, "testUpload/test-blob.log", "src/test/resources/test-azure/")

        // upload directory
        println("url of folder", azureService.upload(container = storageContainer, file = "src/test/resources/1234/",
            objectKey = "testUpload/1234/", isDirectory = true))

        // downlaod directory
        azureService.download(storageContainer, "testUpload/1234/", "src/test/resources/test-azure/", isDirectory = true)

        println("azure signed url", azureService.getSignedURL(storageContainer, "testUpload/test-blob.log", Option(600)))

        val blob = azureService.getObject(storageContainer, "testUpload/test-blob.log")
        println("blob details: ", blob)

        println("upload public url", azureService.upload(container = storageContainer, file = "src/test/resources/test-data.log",
            objectKey = "testUpload/test-data-public.log", isPublic = true))
        println("upload public with expiry url", azureService.upload(container = storageContainer,
            file ="src/test/resources/test-data.log", objectKey = "testUpload/test-data-with-expiry.log",
            isPublic = true, isDirectory = false, Option(600)))
        println("signed path to upload from external client", azureService.getSignedURL(storageContainer, "testUpload/test-data-public1.log", Option(600), Option("w")))

        val keys = azureService.searchObjectkeys(storageContainer, "testUpload/1234/")
        keys.foreach(f => println(f))
        val blobs = azureService.searchObjects(storageContainer, "testUpload/1234/")
        blobs.foreach(f => println(f))

        val objData = azureService.getObjectData(storageContainer, "testUpload/test-blob.log")
        objData.length should be(18)

        // delete directory
//        azureService.deleteObject(storageContainer, "testUpload/1234/", Option(true))
        azureService.deleteObject(storageContainer, "testUpload/test-blob.log")
        //azureUtil.deleteObject(storageContainer, "testUpload/test-data-public.log")
        //azureUtil.deleteObject(storageContainer, "testUpload/test-data-with-expiry.log")
        azureService.deleteObject(storageContainer, "testDuplicate/1234/", Option(true))

        azureService.upload(storageContainer, "src/test/resources/test-extract.zip", "testUpload/test-extract.zip")
        azureService.copyObjects(storageContainer, "testUpload/test-extract.zip", storageContainer, "testDuplicate/test-extract.zip")
        azureService.copyObjects(storageContainer, "testUpload/1234/", storageContainer, "testDuplicate/1234/", isDirectory = true)

        azureService.extractArchive(storageContainer, "testUpload/test-extract.zip", "testUpload/test-extract/")

        azureService.closeContext()
    }
}
