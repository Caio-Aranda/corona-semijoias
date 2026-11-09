package br.com.coronasemijoias.api.testes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas que existem só nos testes (a API ainda não tem rotas de negócio). Entram no contexto
 * apenas por {@code @Import}, nunca na aplicação.
 */
@TestComponent
@RestController
public class RotasDeTeste {

    public record Tempo(Instant instante) {
    }

    public record ValorDeTeste(BigDecimal valor) {
    }

    public record CadastroDeTeste(@NotBlank String nome) {
    }

    @GetMapping("/api/dona/teste/tempo")
    public Tempo tempo() {
        return new Tempo(Instant.parse("2026-11-09T12:00:00Z"));
    }

    @GetMapping("/api/dona/teste/dinheiro")
    public ValorDeTeste valorDaDona() {
        return new ValorDeTeste(new BigDecimal("123.45"));
    }

    @PostMapping("/api/dona/teste/dinheiro")
    public ValorDeTeste eco(@RequestBody ValorDeTeste entrada) {
        return entrada;
    }

    @GetMapping("/api/vendedor/teste/dinheiro")
    public ValorDeTeste valorDoVendedor() {
        return new ValorDeTeste(new BigDecimal("7.5"));
    }

    @GetMapping("/api/auth/teste")
    public Tempo rotaDeLogin() {
        return tempo();
    }

    @PostMapping("/api/dona/teste/validar")
    public CadastroDeTeste validar(@Valid @RequestBody CadastroDeTeste cadastro) {
        return cadastro;
    }

    @GetMapping("/api/dona/teste/falha")
    public void falha() {
        throw new IllegalStateException("segredo-interno-que-nao-pode-vazar");
    }
}
