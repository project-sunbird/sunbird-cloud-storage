package org.sunbird.cloud.storage.conf

import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config

object AppConf {

    lazy val defaultConf = ConfigFactory.load();
    lazy val envConf = ConfigFactory.systemEnvironment();
    lazy val conf = envConf.withFallback(defaultConf);

    def getConfig(key: String): String = {
        if (conf.hasPath(key))
            conf.getString(key);
        else "";
    }

    def getConfig(): Config = {
        return conf;
    }

    def getAwsKey(): String = {
        getConfig("aws_storage_key");
    }

    def getAwsSecret(): String = {
        getConfig("aws_storage_secret");
    }

    def getStorageType(): String = {
        getConfig("cloud_storage_type");
    }

    def getStorageKey(`type`: String): String = {
        if (`type`.equals("aws")) getConfig("aws_storage_key");
        else if (`type`.equals("azure")) getConfig("azure_storage_key");
        else if (`type`.equals("cephs3")) getConfig("cephs3_storage_key");
        else "";
    }

    def getStorageSecret(`type`: String): String = {
        if (`type`.equals("aws")) getConfig("aws_storage_secret");
        else if (`type`.equals("azure")) getConfig("azure_storage_secret");
        else if (`type`.equals("cephs3")) getConfig("cephs3_storage_secret");
        else "";
    }
}
