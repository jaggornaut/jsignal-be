package io.jsignal.be.repository;

import io.jsignal.be.entity.Receiver;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReceiverRepository extends JpaRepository<Receiver, Short> {

    Optional<Receiver> findByName(String name);
}
