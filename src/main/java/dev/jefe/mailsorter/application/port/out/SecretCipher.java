package dev.jefe.mailsorter.application.port.out;

public interface SecretCipher {
    String encrypt(String plain);
    String decrypt(String stored);
}
