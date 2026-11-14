package com.example.webapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Supports atomic application/history writes now and related action updates later. */
@Configuration
@EnableTransactionManagement
public class MongoConfiguration {
    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
        // Spring binds a MongoDB session to TransactionTemplate or @Transactional work.
        return new MongoTransactionManager(databaseFactory);
    }
}
