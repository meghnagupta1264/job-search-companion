package com.example.webapp.account;

import org.springframework.data.mongodb.repository.MongoRepository;

/** One fixed owner record is sufficient while public registration is closed. */
public interface AccountRepository extends MongoRepository<Account, String> {}
