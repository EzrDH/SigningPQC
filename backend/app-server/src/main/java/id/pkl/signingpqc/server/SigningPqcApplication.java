package id.pkl.signingpqc.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** REST lokal SigningPQC. Hanya mendengar di 127.0.0.1 (lihat application.properties). */
@SpringBootApplication
public class SigningPqcApplication {

    public static void main(String[] args) {
        SpringApplication.run(SigningPqcApplication.class, args);
    }
}
