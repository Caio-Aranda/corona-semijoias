package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.creditos.ServicoDeCreditos;
import br.com.coronasemijoias.api.devolucoes.ServicoDeAutorizacoes;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.trocas.ServicoDeTrocas;
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
            "itens_venda", "pagamentos_cliente", "conferencias", "itens_conferencia", "devolucoes", "itens_devolucao",
            "autorizacoes_excecao", "itens_autorizacao", "trocas", "creditos_cliente", "usos_credito", "creditos_sem_saldo");

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
    ServicoDeDevolucoes devolucoes;

    @Autowired
    ServicoDeAutorizacoes autorizacoes;

    @Autowired
    ServicoDeTrocas trocas;

    @Autowired
    ServicoDeCreditos creditos;

    @Autowired
    RegistroDeTabelas registro;

    @Test
    void cadaVendedorTemExatamenteOQueEDeleENadaDoOutro() {
        long semente = Long.getLong("corona.oraculo.semente", System.nanoTime());
        int operacoes = Integer.getInteger("corona.oraculo.operacoes", 300);
        LOG.info("Oráculo de dois vendedores: semente {} ({} operações). Repetir: -Dcorona.oraculo.semente={}", semente,
                operacoes, semente);
        Random r = new Random(semente);
        CenarioDeSync c = new CenarioDeSync(jdbc, usuarios, estoque, remessas, conferencias, vendas)
                .fase6(devolucoes, autorizacoes, trocas, creditos);
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
        // Fase 6 (tarefa 6.7): o que cada vendedor tem para devolver, trocar e pagar com crédito.
        record Vendida(UsuarioDeTeste dono, UUID venda, UUID maleta, UUID produto, UUID cliente) {
        }
        List<Vendida> pagas = new ArrayList<>();
        List<UUID> devolucoesFeitas = new ArrayList<>();
        List<UUID> pedidos = new ArrayList<>();
        Map<UUID, Vendida> creditosDaTroca = new LinkedHashMap<>();
        CopiaLocal copiaA = new CopiaLocal();
        CopiaLocal copiaB = new CopiaLocal();

        for (int i = 0; i < operacoes; i++) {
            UsuarioDeTeste v = vendedores.get(r.nextInt(2));
            UUID peca = pecas.get(r.nextInt(pecas.size()));
            switch (r.nextInt(24)) {
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
                case 12 -> c.transferir(clientes.get(r.nextInt(clientes.size())), v.vendedorId());
                // --- Fase 6 (tarefa 6.7) ---
                case 13, 14 -> qualquer(r, conferidas).ifPresent(m -> {
                    UUID item = jdbc.queryForList("SELECT produto_id FROM corona.itens_remessa WHERE remessa_id = ? AND deleted_at IS NULL",
                            UUID.class, m).get(0);
                    UUID cliente = clientes.get(r.nextInt(clientes.size()));
                    if (c.saldo(m, item) > 0) {
                        c.venderPago(conferidas.get(m), m, cliente, item)
                                .ifPresent(venda -> pagas.add(new Vendida(conferidas.get(m), venda, m, item, cliente)));
                    }
                });
                case 15 -> umaDe(r, pagas).ifPresent(p -> c.devolver(p.dono(), p.venda(), p.produto(), r.nextBoolean() ? "BOA" : "DEFEITO")
                        .ifPresent(devolucoesFeitas::add));
                case 16 -> umaDe(r, pagas).ifPresent(p -> c.pedir(p.dono(), p.venda(), p.produto()).ifPresent(pedidos::add));
                case 17 -> umaDe(r, pedidos).ifPresent(ped -> c.decidir(ped, r.nextBoolean()));
                case 18 -> umaDe(r, pagas).ifPresent(p -> {
                    // B de uma maleta conferida do mesmo vendedor.
                    conferidas.entrySet().stream().filter(e -> e.getValue().equals(p.dono())).findFirst().ifPresent(e -> {
                        UUID pecaB = jdbc.queryForList("SELECT produto_id FROM corona.itens_remessa WHERE remessa_id = ? AND deleted_at IS NULL",
                                UUID.class, e.getKey()).get(0);
                        if (c.saldo(e.getKey(), pecaB) > 0) {
                            c.trocar(p.dono(), p.venda(), p.produto(), e.getKey(), pecaB).ifPresent(cr -> creditosDaTroca.put(cr,
                                    new Vendida(p.dono(), p.venda(), e.getKey(), pecaB, p.cliente())));
                        }
                    });
                });
                case 19, 20 -> umaChave(r, creditosDaTroca).ifPresent(cr -> {
                    Vendida onde = creditosDaTroca.get(cr);
                    UUID dono = jdbc.queryForObject("SELECT vendedor_id FROM corona.creditos_cliente WHERE id = ?", UUID.class, cr);
                    if (dono.equals(onde.dono().vendedorId()) && c.saldo(onde.maleta(), onde.produto()) > 0) {
                        // Às vezes pelo envio pedindo o preço inteiro: acima do saldo, a dona revisa.
                        c.venderComCredito(onde.dono(), onde.maleta(), onde.cliente(), onde.produto(), cr, r.nextBoolean());
                    }
                });
                case 21 -> umaChave(r, creditosDaTroca).ifPresent(cr -> c.transferirCredito(cr,
                        (creditosDaTroca.get(cr).dono().equals(a) ? b : a).vendedorId()));
                case 22 -> {
                    if (r.nextBoolean()) {
                        umaDe(r, devolucoesFeitas).ifPresent(c::cancelarDevolucao);
                    } else {
                        jdbc.queryForList("SELECT id FROM corona.trocas WHERE situacao = 'ATIVA' AND vendedor_id IN (?, ?)", UUID.class,
                                a.vendedorId(), b.vendedorId()).stream().findAny().ifPresent(c::cancelarTroca);
                    }
                }
                default -> {
                    jdbc.queryForList("""
                            SELECT s.id FROM corona.creditos_sem_saldo s JOIN corona.vendas v ON v.id = s.venda_id
                             WHERE s.situacao = 'EM_REVISAO' AND v.vendedor_id IN (?, ?)""", UUID.class, a.vendedorId(),
                            b.vendedorId()).stream().findAny().ifPresent(id -> c.revisarSemSaldo(id, r.nextBoolean()));
                    umaDe(r, devolucoesFeitas).ifPresent(d -> c.revisarDevolucao(d, r.nextBoolean()));
                }
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

    private static <T> java.util.Optional<T> umaDe(Random r, List<T> lista) {
        return lista.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(lista.get(r.nextInt(lista.size())));
    }

    private static <T> java.util.Optional<UUID> umaChave(Random r, Map<UUID, T> mapa) {
        return qualquer(r, mapa);
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
        // Nenhuma coluna com UUID, em nenhuma linha que chegou, aponta para uma linha privada do outro (tarefa 6.7, decisão 3).
        Set<UUID> privadasDoOutro = new HashSet<>();
        for (String t : PRIVADAS) {
            Set<UUID> soDoOutro = new HashSet<>(doOutro.getOrDefault(t, Map.of()).keySet());
            soDoOutro.removeAll(meu.getOrDefault(t, Map.of()).keySet());
            privadasDoOutro.addAll(soDoOutro);
        }
        Map<String, Map<UUID, Map<String, Object>>> colunas = new HashMap<>();
        copia.tabelas.forEach((t, linhas) -> {
            Map<UUID, Map<String, Object>> m = new HashMap<>();
            linhas.forEach((id, g) -> m.put(id, g.colunas()));
            colunas.put(t, m);
        });
        assertThat(idsDoOutro(colunas, privadasDoOutro)).as("semente %d: ids de linhas privadas do outro na cópia de %s", semente, quem)
                .isEmpty();
        // Nenhuma coluna de custo nem de estoque da loja em nada que chegou.
        copia.tabelas.forEach((t, linhas) -> linhas.values().forEach(g -> assertThat(g.colunas().keySet())
                .noneMatch(col -> col.contains("custo") || col.contains("estoque") || col.contains("fornecedor"))));
    }

    /**
     * Onde a cópia cita um dos {@code proibidos}: o id da linha ou qualquer coluna com UUID ("tabela.coluna=id"). Serve ao
     * oráculo e ao {@link PullDaFase6Test}.
     */
    static List<String> idsDoOutro(Map<String, ? extends Map<UUID, ?>> copia, Set<UUID> proibidos) {
        List<String> achados = new ArrayList<>();
        copia.forEach((t, linhas) -> linhas.forEach((id, linha) -> {
            if (proibidos.contains(id)) {
                achados.add(t + ".id=" + id);
            }
            Map<?, ?> cols = linha instanceof LinhaDoPull l ? l.colunas() : (Map<?, ?>) linha;
            cols.forEach((col, valor) -> {
                UUID u = comoUuid(valor);
                if (u != null && proibidos.contains(u)) {
                    achados.add(t + "." + col + "=" + u);
                }
            });
        }));
        return achados;
    }

    private static UUID comoUuid(Object valor) {
        if (valor instanceof UUID u) {
            return u;
        }
        if (valor instanceof String s && s.length() == 36) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException naoE) {
                return null;
            }
        }
        return null;
    }

    /** Prova de que a varredura pega o furo: com credito_origem_id de volta, o crédito do outro vendedor aparece. */
    @Test
    void aVarreduraDeIdsPegaUmaColunaQueApontaParaOOutro() {
        UUID doOutro = UUID.randomUUID();
        UUID meu = UUID.randomUUID();
        Map<String, Map<UUID, Map<String, Object>>> comOFuro = Map.of("creditos_cliente",
                Map.of(meu, Map.of("origem", "TRANSFERENCIA", "credito_origem_id", doOutro.toString())));
        assertThat(idsDoOutro(comOFuro, Set.of(doOutro))).containsExactly("creditos_cliente.credito_origem_id=" + doOutro);
        Map<String, Map<UUID, Map<String, Object>>> semOFuro = Map.of("creditos_cliente",
                Map.of(meu, Map.of("origem", "TRANSFERENCIA")));
        assertThat(idsDoOutro(semOFuro, Set.of(doOutro))).isEmpty();
        // E o registro de produção não entrega a coluna.
        assertThat(RegistroDeTabelas.daAplicacao().sincronizavel("creditos_cliente").orElseThrow().colunas())
                .doesNotContain("credito_origem_id");
    }

    private Map<String, Map<UUID, Linha>> oraculo(UUID v) {
        return CenarioDeSync.oraculo(jdbc, v);
    }
}
