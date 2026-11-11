package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.remessas.ServicoDeRemessas;
import br.com.coronasemijoias.api.sync.CenarioDeSync.CopiaLocal;
import br.com.coronasemijoias.api.sync.CenarioDeSync.Linha;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas;
import br.com.coronasemijoias.api.vendedor.ServicoDeConferencia;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O oráculo de dois vendedores (ADR-0013, item 5, prova 1; tarefa 5.4). Uma consulta de referência, escrita à parte e
 * sem usar o código do pull (leitura das tabelas inteiras e regras em Java), diz o que é de A e o que é de B. O teste faz
 * centenas de operações aleatórias com semente impressa (a dona cria peças, monta, envia e exclui maletas, muda preço,
 * troca foto, exclui código, cadastra e transfere cliente; A e B conferem e vendem) e, entre elas, A e B puxam em
 * momentos aleatórios, com lotes de 1 a 50. No fim: a cópia local de cada um é IGUAL ao oráculo (todo id, toda versão,
 * toda lápide) e nenhum dado privado de B aparece em A, nem o contrário.
 *
 * <p>Repetir uma falha: {@code -Dcorona.oraculo.semente=<a semente impressa>}; mais operações:
 * {@code -Dcorona.oraculo.operacoes=2000}.
 */
class OraculoDeDoisVendedoresTest extends TesteDeIntegracao {

    private static final Logger LOG = LoggerFactory.getLogger(OraculoDeDoisVendedoresTest.class);

    /** Tabelas privadas de cada vendedor (as da loja e as peças podem ser dos dois). */
    private static final Set<String> PRIVADAS = Set.of("remessas", "itens_remessa", "movimentos_remessa", "vendas",
            "itens_venda", "pagamentos_cliente", "conferencias", "itens_conferencia");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    ServicoDeEstoque estoque;

    @Autowired
    ServicoDeRemessas remessas;

    @Autowired
    ServicoDeConferencia conferencias;

    @Autowired
    ServicoDeVendas vendas;

    @Autowired
    ServicoDePull pull;

    @Autowired
    RegistroDeTabelas registro;

    @Test
    void cadaVendedorTemExatamenteOQueEDeleENadaDoOutro() {
        long semente = Long.getLong("corona.oraculo.semente", System.nanoTime());
        int operacoes = Integer.getInteger("corona.oraculo.operacoes", 300);
        LOG.info("Oráculo de dois vendedores: semente {} ({} operações). Repetir: -Dcorona.oraculo.semente={}", semente,
                operacoes, semente);
        Random r = new Random(semente);
        CenarioDeSync c = new CenarioDeSync(jdbc, usuarios, estoque, remessas, conferencias, vendas);
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        usuarios.token(a); // registra o computador em uso de cada um
        usuarios.token(b);
        List<UsuarioDeTeste> vendedores = List.of(a, b);

        List<UUID> pecas = new ArrayList<>();
        Map<UUID, UsuarioDeTeste> rascunhos = new LinkedHashMap<>();
        Map<UUID, UsuarioDeTeste> enviadas = new LinkedHashMap<>();
        Map<UUID, UsuarioDeTeste> conferidas = new LinkedHashMap<>();
        List<UUID> clientes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pecas.add(c.peca(new BigDecimal("30.00"), 200));
        }
        clientes.add(c.cliente(a.vendedorId()));
        clientes.add(c.cliente(b.vendedorId()));
        CopiaLocal copiaA = new CopiaLocal();
        CopiaLocal copiaB = new CopiaLocal();

        for (int i = 0; i < operacoes; i++) {
            UsuarioDeTeste v = vendedores.get(r.nextInt(2));
            UUID peca = pecas.get(r.nextInt(pecas.size()));
            switch (r.nextInt(13)) {
                case 0 -> pecas.add(c.peca(new BigDecimal(10 + r.nextInt(90) + ".00"), 200));
                case 1 -> c.mudarPreco(peca);
                case 2 -> c.acrescentarCodigo(peca);
                case 3 -> c.excluirUmCodigoSecundario(peca);
                case 4 -> c.trocarFoto(peca);
                case 5, 6 -> {
                    Map<UUID, Integer> itens = new HashMap<>();
                    for (int k = 0, n = 1 + r.nextInt(3); k < n; k++) {
                        itens.put(pecas.get(r.nextInt(pecas.size())), 1 + r.nextInt(3));
                    }
                    rascunhos.put(c.rascunho(v, itens), v);
                }
                case 7 -> qualquer(r, rascunhos).ifPresent(m -> {
                    c.enviar(m);
                    enviadas.put(m, rascunhos.remove(m));
                });
                case 8 -> qualquer(r, rascunhos).ifPresent(m -> {
                    c.excluirRascunho(m);
                    rascunhos.remove(m);
                });
                case 9 -> qualquer(r, enviadas).ifPresent(m -> {
                    UsuarioDeTeste dono = enviadas.remove(m);
                    c.conferir(dono, m);
                    conferidas.put(m, dono);
                });
                case 10 -> qualquer(r, conferidas).ifPresent(m -> {
                    UUID item = jdbc.queryForList("SELECT produto_id FROM corona.itens_remessa WHERE remessa_id = ? AND deleted_at IS NULL",
                            UUID.class, m).get(0);
                    if (c.saldo(m, item) > 0) {
                        c.vender(conferidas.get(m), m, clientes.get(r.nextInt(clientes.size())), item);
                    }
                });
                case 11 -> clientes.add(c.cliente(r.nextBoolean() ? v.vendedorId() : null));
                default -> c.transferir(clientes.get(r.nextInt(clientes.size())), v.vendedorId());
            }
            puxarAsVezes(r, copiaA, a);
            puxarAsVezes(r, copiaB, b);
        }
        copiaA.puxarTudo(pull, a.vendedorId(), 1000);
        copiaB.puxarTudo(pull, b.vendedorId(), 1000);

        conferir(copiaA, oraculo(a.vendedorId()), "A", semente);
        conferir(copiaB, oraculo(b.vendedorId()), "B", semente);
        nadaPrivadoDoOutro(copiaA, b.vendedorId(), oraculo(b.vendedorId()), oraculo(a.vendedorId()), "A", semente);
        nadaPrivadoDoOutro(copiaB, a.vendedorId(), oraculo(a.vendedorId()), oraculo(b.vendedorId()), "B", semente);
        assertThat(enviadas.size() + conferidas.size()).as("semente %d: o cenário enviou maletas", semente).isPositive();
        assertThat(oraculo(a.vendedorId()).getOrDefault("remessas", Map.of())).as("semente %d: A recebeu maletas", semente)
                .isNotEmpty();
    }

    private void puxarAsVezes(Random r, CopiaLocal copia, UsuarioDeTeste v) {
        if (r.nextInt(4) != 0) {
            return;
        }
        for (int lotes = 1 + r.nextInt(5); lotes > 0; lotes--) {
            LoteDoPull l = pull.puxar(copia.cursor, 1 + r.nextInt(50), new FiltroPorVendedor(v.vendedorId()));
            copia.aplicar(l);
            if (!l.haMais()) {
                return;
            }
        }
    }

    private static <T> java.util.Optional<UUID> qualquer(Random r, Map<UUID, T> mapa) {
        if (mapa.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ArrayList<>(mapa.keySet()).get(r.nextInt(mapa.size())));
    }

    private void conferir(CopiaLocal copia, Map<String, Map<UUID, Linha>> esperado, String quem, long semente) {
        for (TabelaSincronizavel t : registro.sincronizaveis()) {
            if (!t.regra().declarada()) {
                continue;
            }
            Map<UUID, Linha> deveria = esperado.getOrDefault(t.nome(), Map.of());
            Map<UUID, Linha> tem = new HashMap<>();
            copia.tabelas.getOrDefault(t.nome(), Map.of())
                    .forEach((id, g) -> tem.put(id, new Linha(g.versao(), g.excluida())));
            assertThat(tem).as("semente %d: a cópia de %s na tabela %s é igual ao oráculo", semente, quem, t.nome())
                    .isEqualTo(deveria);
        }
    }

    private static void nadaPrivadoDoOutro(CopiaLocal copia, UUID outro, Map<String, Map<UUID, Linha>> doOutro,
            Map<String, Map<UUID, Linha>> meu, String quem, long semente) {
        for (String t : PRIVADAS) {
            Set<UUID> soDoOutro = new HashSet<>(doOutro.getOrDefault(t, Map.of()).keySet());
            soDoOutro.removeAll(meu.getOrDefault(t, Map.of()).keySet());
            assertThat(copia.ids(t)).as("semente %d: nada de %s do outro vendedor na cópia de %s", semente, t, quem)
                    .noneMatch(soDoOutro::contains);
        }
        // Clientes: do cliente atendido pelo outro, só o diretório (nome, CPF, telefone, cidade, ativo).
        copia.tabelas.getOrDefault("clientes", Map.of()).forEach((id, g) -> {
            if (g.colunas().get("vendedor_responsavel_id") == null) {
                for (String privada : List.of("whatsapp", "observacoes", "data_nascimento", "endereco_logradouro")) {
                    assertThat(g.colunas().get(privada)).as("semente %d: %s do cliente %s na cópia de %s", semente, privada, id, quem)
                            .isNull();
                }
            }
            assertThat(g.colunas().get("vendedor_responsavel_id")).as("semente %d", semente).isNotEqualTo(outro);
        });
        // Nenhuma coluna de custo nem de estoque da loja em nada que chegou.
        copia.tabelas.forEach((t, linhas) -> linhas.values().forEach(g -> assertThat(g.colunas().keySet())
                .noneMatch(col -> col.contains("custo") || col.contains("estoque") || col.contains("fornecedor"))));
    }

    private Map<String, Map<UUID, Linha>> oraculo(UUID v) {
        return CenarioDeSync.oraculo(jdbc, v);
    }
}
