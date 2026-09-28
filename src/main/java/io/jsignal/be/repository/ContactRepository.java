package io.jsignal.be.repository;

import io.jsignal.be.entity.Contact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    Optional<Contact> findByDomainAndIdentifier(String domain, String identifier);
}
