package com.innowise.userservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Entry point of the User Service.
 *
 * <p>{@link UserDetailsServiceAutoConfiguration} is excluded on purpose: it creates an in-memory
 * user with a random password and prints that password to the log. This service never
 * authenticates through Spring Security's username and password support: callers bring a token
 * of the Authentication Service, or the internal key of another service.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class UserServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(UserServiceApplication.class, args);
  }

}
