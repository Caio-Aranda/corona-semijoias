package br.com.coronasemijoias.api;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CoronaApiApplication {

    public static void main(String[] args) {
        // Datas sempre em UTC (ADR-0002), mesmo que a JVM não tenha recebido -Duser.timezone.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(CoronaApiApplication.class, args);
    }
}
