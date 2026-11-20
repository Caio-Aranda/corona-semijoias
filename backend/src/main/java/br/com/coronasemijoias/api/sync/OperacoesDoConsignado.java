package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.consignado.ContextoDoConsignado;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.Devolvido;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.EstornoDoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.NovoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.ReciboDoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.Visto;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.ItemDaNota;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.NovaNota;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.PecaQueEntra;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.PecaQueVolta;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.Remarcacao;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.Reposicao;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.Troca;
import br.com.coronasemijoias.api.consignado.ServicoDeRecibos;
import br.com.coronasemijoias.api.consignado.ServicoDeRecibos.EstornoDoRecibo;
import br.com.coronasemijoias.api.consignado.ServicoDeRecibos.FormaPaga;
import br.com.coronasemijoias.api.consignado.ServicoDeRecibos.NovoPagamento;
import br.com.coronasemijoias.api.consignado.ServicoDeSemanasDoVendedor;
import br.com.coronasemijoias.api.consignado.ServicoDeSemanasDoVendedor.SemanaNova;
import br.com.coronasemijoias.api.sync.OperacoesDoVendedor.Aplicada;
import br.com.coronasemijoias.api.sync.OperacoesDoVendedor.Contexto;
import br.com.coronasemijoias.api.vendedor.ClientesDoConsignado;
import br.com.coronasemijoias.api.vendedor.ClientesDoConsignado.Alteracao;
import br.com.coronasemijoias.api.vendedor.ClientesDoConsignado.Novo;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Os tipos de operação do consignado ao cliente (tarefa 9.1; ADR-0016, item 13): o conteúdo do formato 1, a forma canônica
 * (campos em ordem fixa, listas ordenadas pelo id, dinheiro em centavos exatos, nulo como ∅), os registros de que cada um
 * depende e a aplicação, SEMPRE pelos serviços do pacote {@code consignado}. Aqui não mora regra nenhuma. Também trata o
 * formato 2 de {@code CLIENTE_NOVO} e {@code CLIENTE_ALTERADO} (cadastro do cliente do consignado).
 */
@Component
public class OperacoesDoConsignado {

    /** Tipos novos, todos no formato 1. */
    static final Set<String> TIPOS = Set.of("SEMANA_NOVA", "NOTA_CONSIGNADO", "REPOSICAO_NOTA", "TROCA_NA_NOTA", "REMARCAR_NOTA",
            "ACERTO_NOTA", "PAGAMENTO_CONSIGNADO", "ESTORNO_ACERTO_NOTA", "ESTORNO_RECIBO");

    /** É um tipo do consignado, ou o formato 2 do cadastro do cliente? */
    static boolean trata(String tipo, int formato) {
        return TIPOS.contains(tipo) || (formato == 2 && ("CLIENTE_NOVO".equals(tipo) || "CLIENTE_ALTERADO".equals(tipo)));
    }

    /** O conteúdo lido é de um tipo desta classe (ou o cadastro do cliente, formato 2)? */
    static boolean pertence(Object dados) {
        return dados instanceof Novo || dados.getClass().getName().startsWith(OperacoesDoConsignado.class.getName() + "$");
    }

    // --- conteúdo ---

    public record DadosDaSemanaNova(UUID id, Integer numero, Instant abertaEm) {
    }

    public record DadosDaNota(UUID id, UUID semanaId, Integer sequencia, UUID clienteId, UUID notaOrigemId, LocalDate dataCobranca,
            Instant saidaEm, List<ItemDaNota> itens, BigDecimal totalDxVisto) {
    }

    public record DadosDaReposicao(UUID id, UUID notaId, Instant feitaEm, List<ItemDaNota> itens) {
    }

    public record DadosDaTrocaNaNota(UUID id, UUID notaId, Instant trocadaEm, PecaQueVolta volta, PecaQueEntra entra) {
    }

    public record DadosDaRemarcacao(UUID id, UUID notaId, LocalDate dataCobranca, Instant remarcadaEm) {
    }

    public record DadosDoAcerto(UUID id, UUID notaId, UUID semanaId, Instant acertadoEm, BigDecimal comissaoPercentual,
            List<Devolvido> devolvidos, ReciboDoAcerto recibo, Visto visto, LocalDate dataDoAcerto) {
    }

    public record DadosDoPagamento(UUID id, UUID clienteId, UUID semanaId, Integer sequencia, Instant pagoEm, List<FormaPaga> formas,
            BigDecimal pendenciaVista, LocalDate dataDoPagamento) {
    }

    public record DadosDoEstornoDoAcerto(UUID id, UUID acertoId, String motivo, Instant estornadoEm) {
    }

    public record DadosDoEstornoDoRecibo(UUID id, UUID reciboId, String motivo, Instant estornadoEm) {
    }

    public record DadosDaAlteracaoV2(UUID clienteId, Map<String, Alteracao> campos) {
    }

    /** A classe de cada tipo (e formato). */
    static Class<?> classe(String tipo, int formato) {
        return switch (tipo) {
            case "SEMANA_NOVA" -> DadosDaSemanaNova.class;
            case "NOTA_CONSIGNADO" -> DadosDaNota.class;
            case "REPOSICAO_NOTA" -> DadosDaReposicao.class;
            case "TROCA_NA_NOTA" -> DadosDaTrocaNaNota.class;
            case "REMARCAR_NOTA" -> DadosDaRemarcacao.class;
            case "ACERTO_NOTA" -> DadosDoAcerto.class;
            case "PAGAMENTO_CONSIGNADO" -> DadosDoPagamento.class;
            case "ESTORNO_ACERTO_NOTA" -> DadosDoEstornoDoAcerto.class;
            case "ESTORNO_RECIBO" -> DadosDoEstornoDoRecibo.class;
            case "CLIENTE_NOVO" -> Novo.class;
            default -> DadosDaAlteracaoV2.class;
        };
    }

    /** O id do registro que a operação cria; precisa ser o {@code operacaoId} (nulo na alteração do cliente). */
    static UUID idDoRegistro(Object d) {
        return switch (d) {
            case DadosDaSemanaNova x -> x.id();
            case DadosDaNota x -> x.id();
            case DadosDaReposicao x -> x.id();
            case DadosDaTrocaNaNota x -> x.id();
            case DadosDaRemarcacao x -> x.id();
            case DadosDoAcerto x -> x.id();
            case DadosDoPagamento x -> x.id();
            case DadosDoEstornoDoAcerto x -> x.id();
            case DadosDoEstornoDoRecibo x -> x.id();
            case Novo x -> x.id();
            default -> null;
        };
    }

    /** A hora que o computador declarou. */
    static Instant horaDeclarada(Object d) {
        return switch (d) {
            case DadosDaSemanaNova x -> x.abertaEm();
            case DadosDaNota x -> x.saidaEm();
            case DadosDaReposicao x -> x.feitaEm();
            case DadosDaTrocaNaNota x -> x.trocadaEm();
            case DadosDaRemarcacao x -> x.remarcadaEm();
            case DadosDoAcerto x -> x.acertadoEm();
            case DadosDoPagamento x -> x.pagoEm();
            case DadosDoEstornoDoAcerto x -> x.estornadoEm();
            case DadosDoEstornoDoRecibo x -> x.estornadoEm();
            default -> null;
        };
    }

    /** Os registros de que a operação depende (para a recusa {@code DEPENDE_DE_ENVIO_RECUSADO}). */
    static List<UUID> dependencias(Object d) {
        List<UUID> ids = new ArrayList<>();
        switch (d) {
            case DadosDaNota x -> {
                ids.add(x.semanaId());
                ids.add(x.clienteId());
                ids.add(x.notaOrigemId());
                itensDe(x.itens()).forEach(i -> ids.add(i.remessaId()));
            }
            case DadosDaReposicao x -> ids.add(x.notaId());
            case DadosDaTrocaNaNota x -> ids.add(x.notaId());
            case DadosDaRemarcacao x -> ids.add(x.notaId());
            case DadosDoAcerto x -> {
                ids.add(x.notaId());
                ids.add(x.semanaId());
            }
            case DadosDoPagamento x -> {
                ids.add(x.clienteId());
                ids.add(x.semanaId());
            }
            case DadosDoEstornoDoAcerto x -> ids.add(x.acertoId());
            case DadosDoEstornoDoRecibo x -> ids.add(x.reciboId());
            case Novo x -> ids.add(x.indicadoPorClienteId());
            case DadosDaAlteracaoV2 x -> ids.add(x.clienteId());
            default -> {
            }
        }
        ids.removeIf(Objects::isNull);
        return ids;
    }

    private static List<ItemDaNota> itensDe(List<ItemDaNota> itens) {
        List<ItemDaNota> lista = new ArrayList<>(itens == null ? List.of() : itens);
        lista.removeIf(Objects::isNull);
        lista.sort(Comparator.comparing(i -> String.valueOf(i.id())));
        return lista;
    }

    // --- forma canônica ---

    static String canonico(Object dados) {
        StringBuilder s = new StringBuilder();
        switch (dados) {
            case DadosDaSemanaNova d -> {
                campo(s, "id", d.id());
                campo(s, "numero", d.numero());
                campo(s, "abertaEm", d.abertaEm());
            }
            case DadosDaNota d -> {
                campo(s, "id", d.id());
                campo(s, "semanaId", d.semanaId());
                campo(s, "sequencia", d.sequencia());
                campo(s, "clienteId", d.clienteId());
                campo(s, "notaOrigemId", d.notaOrigemId());
                campo(s, "dataCobranca", d.dataCobranca());
                campo(s, "saidaEm", d.saidaEm());
                itens(s, d.itens());
                campo(s, "totalDxVisto", dinheiro(d.totalDxVisto()));
            }
            case DadosDaReposicao d -> {
                campo(s, "id", d.id());
                campo(s, "notaId", d.notaId());
                campo(s, "feitaEm", d.feitaEm());
                itens(s, d.itens());
            }
            case DadosDaTrocaNaNota d -> {
                campo(s, "id", d.id());
                campo(s, "notaId", d.notaId());
                campo(s, "trocadaEm", d.trocadaEm());
                PecaQueVolta v = d.volta();
                s.append("volta={");
                if (v != null) {
                    s.append(v.itemId()).append(':').append(v.produtoId()).append(':').append(v.remessaId()).append(':')
                            .append(v.movimentoId());
                }
                s.append("}|");
                PecaQueEntra e = d.entra();
                s.append("entra={");
                if (e != null) {
                    s.append(e.itemId()).append(':').append(e.produtoId()).append(':').append(e.remessaId()).append(':')
                            .append(dinheiro(e.precoUnitario())).append(':').append(e.movimentoId());
                }
                s.append("}|");
            }
            case DadosDaRemarcacao d -> {
                campo(s, "id", d.id());
                campo(s, "notaId", d.notaId());
                campo(s, "dataCobranca", d.dataCobranca());
                campo(s, "remarcadaEm", d.remarcadaEm());
            }
            case DadosDoAcerto d -> {
                campo(s, "id", d.id());
                campo(s, "notaId", d.notaId());
                campo(s, "semanaId", d.semanaId());
                campo(s, "acertadoEm", d.acertadoEm());
                campo(s, "comissaoPercentual", pct(d.comissaoPercentual()));
                List<Devolvido> dev = new ArrayList<>(d.devolvidos() == null ? List.of() : d.devolvidos());
                dev.removeIf(Objects::isNull);
                dev.sort(Comparator.comparing(x -> String.valueOf(x.movimentoId())));
                s.append("devolvidos=[");
                dev.forEach(x -> s.append(x.itemAcertoId()).append(':').append(x.produtoId()).append(':').append(x.remessaId())
                        .append(':').append(x.quantidade()).append(':').append(x.movimentoId()).append(';'));
                s.append("]|");
                ReciboDoAcerto r = d.recibo();
                s.append("recibo={");
                if (r != null) {
                    campo(s, "id", r.id());
                    campo(s, "sequencia", r.sequencia());
                    formas(s, r.formas());
                }
                s.append("}|");
                Visto v = d.visto();
                s.append("visto={");
                if (v != null) {
                    campo(s, "vd", dinheiro(v.valorVd()));
                    campo(s, "comissao", dinheiro(v.comissao()));
                    campo(s, "bruto", dinheiro(v.bruto()));
                    campo(s, "pendencias", dinheiro(v.pendenciasAnteriores()));
                    campo(s, "total", dinheiro(v.totalAReceber()));
                    campo(s, "pago", dinheiro(v.totalPago()));
                    campo(s, "devendo", dinheiro(v.ficouDevendo()));
                }
                s.append("}|");
                if (d.dataDoAcerto() != null) {
                    campo(s, "dataDoAcerto", d.dataDoAcerto());
                }
            }
            case DadosDoPagamento d -> {
                campo(s, "id", d.id());
                campo(s, "clienteId", d.clienteId());
                campo(s, "semanaId", d.semanaId());
                campo(s, "sequencia", d.sequencia());
                campo(s, "pagoEm", d.pagoEm());
                formas(s, d.formas());
                campo(s, "pendenciaVista", dinheiro(d.pendenciaVista()));
                if (d.dataDoPagamento() != null) {
                    campo(s, "dataDoPagamento", d.dataDoPagamento());
                }
            }
            case DadosDoEstornoDoAcerto d -> {
                campo(s, "id", d.id());
                campo(s, "acertoId", d.acertoId());
                campo(s, "motivo", d.motivo());
                campo(s, "estornadoEm", d.estornadoEm());
            }
            case DadosDoEstornoDoRecibo d -> {
                campo(s, "id", d.id());
                campo(s, "reciboId", d.reciboId());
                campo(s, "motivo", d.motivo());
                campo(s, "estornadoEm", d.estornadoEm());
            }
            case Novo d -> {
                campo(s, "id", d.id());
                campo(s, "nome", d.nome());
                campo(s, "documento", d.documento());
                campo(s, "documentoTipo", d.documentoTipo());
                campo(s, "dataNascimento", d.dataNascimento());
                campo(s, "telefone", d.telefone());
                campo(s, "whatsapp", d.whatsapp());
                campo(s, "enderecoLogradouro", d.enderecoLogradouro());
                campo(s, "enderecoNumero", d.enderecoNumero());
                campo(s, "enderecoComplemento", d.enderecoComplemento());
                campo(s, "enderecoBairro", d.enderecoBairro());
                campo(s, "enderecoCidade", d.enderecoCidade());
                campo(s, "enderecoUf", d.enderecoUf());
                campo(s, "enderecoCep", d.enderecoCep());
                campo(s, "indicadoPorClienteId", d.indicadoPorClienteId());
                campo(s, "indicadoPorNome", d.indicadoPorNome());
                campo(s, "comoConheceu", d.comoConheceu());
                campo(s, "comissaoPercentual", pct(d.comissaoPercentual()));
                campo(s, "observacoes", d.observacoes());
                campo(s, "fotoHash", d.fotoHash());
                campo(s, "fotoDocumentoFrenteHash", d.fotoDocumentoFrenteHash());
                campo(s, "fotoDocumentoVersoHash", d.fotoDocumentoVersoHash());
            }
            case DadosDaAlteracaoV2 d -> {
                campo(s, "clienteId", d.clienteId());
                new TreeMap<>(d.campos() == null ? Map.<String, Alteracao>of() : d.campos()).forEach((campo, a) -> s.append(campo)
                        .append("={").append(a == null ? "∅" : a.antes()).append("→").append(a == null ? "∅" : a.depois()).append("}|"));
            }
            default -> throw new IllegalStateException("tipo do consignado desconhecido");
        }
        return s.toString();
    }

    private static void itens(StringBuilder s, List<ItemDaNota> itens) {
        s.append("itens=[");
        itensDe(itens).forEach(i -> s.append(i.id()).append(':').append(i.produtoId()).append(':').append(i.remessaId()).append(':')
                .append(i.quantidade()).append(':').append(dinheiro(i.precoUnitario())).append(':').append(i.movimentoId()).append(';'));
        s.append("]|");
    }

    private static void formas(StringBuilder s, List<FormaPaga> formas) {
        List<FormaPaga> lista = new ArrayList<>(formas == null ? List.of() : formas);
        lista.removeIf(Objects::isNull);
        lista.sort(Comparator.comparing(f -> String.valueOf(f.forma())));
        s.append("formas=[");
        lista.forEach(f -> s.append(f.forma()).append(':').append(dinheiro(f.valor())).append(';'));
        s.append("]|");
    }

    private static void campo(StringBuilder s, String nome, Object valor) {
        s.append(nome).append('=').append(valor == null ? "∅" : valor.toString()).append('|');
    }

    private static String dinheiro(BigDecimal valor) {
        return OperacoesDaFase6.dinheiro(valor);
    }

    /** A porcentagem em centésimos exatos ("30" e "30.00" dão "3000"). */
    private static String pct(BigDecimal valor) {
        return valor == null ? null : valor.movePointRight(2).toBigIntegerExact().toString();
    }

    // --- aplicação ---

    private final ServicoDeSemanasDoVendedor semanas;
    private final ServicoDeNotas notas;
    private final ServicoDeAcertosDaNota acertos;
    private final ServicoDeRecibos recibos;
    private final ClientesDoConsignado clientes;

    public OperacoesDoConsignado(ServicoDeSemanasDoVendedor semanas, ServicoDeNotas notas, ServicoDeAcertosDaNota acertos,
            ServicoDeRecibos recibos, ClientesDoConsignado clientes) {
        this.semanas = semanas;
        this.notas = notas;
        this.acertos = acertos;
        this.recibos = recibos;
        this.clientes = clientes;
    }

    /** Aplica pelo serviço do consignado (na transação de quem chama). A hora é corrigida pela diferença do relógio. */
    Aplicada aplicar(Object dados, Contexto ctx) {
        Instant corrigida = OperacoesDoVendedor.corrigir(horaDeclarada(dados), ctx, ctx.ultimoContatoServidor());
        boolean ajustada = horaDeclarada(dados) != null
                && java.time.Duration.between(horaDeclarada(dados), corrigida).abs().compareTo(OperacoesDoVendedor.AJUSTE_QUE_MARCA) > 0;
        ContextoDoConsignado c = new ContextoDoConsignado(ctx.vendedorId(), ctx.usuarioId(), ctx.aparelho(), corrigida, ajustada);
        boolean ja = switch (dados) {
            case DadosDaSemanaNova d -> semanas.abrir(c, new SemanaNova(d.id(), d.numero(), corrigida));
            case DadosDaNota d -> notas.criar(c, new NovaNota(d.id(), d.semanaId(), d.sequencia(), d.clienteId(), d.notaOrigemId(),
                    d.dataCobranca(), d.itens(), d.totalDxVisto()));
            case DadosDaReposicao d -> notas.reporNaNota(c, new Reposicao(d.id(), d.notaId(), d.itens()));
            case DadosDaTrocaNaNota d -> notas.trocar(c, new Troca(d.id(), d.notaId(), d.volta(), d.entra()));
            case DadosDaRemarcacao d -> notas.remarcar(c, new Remarcacao(d.id(), d.notaId(), d.dataCobranca()));
            case DadosDoAcerto d -> acertos.acertar(c, new NovoAcerto(d.id(), d.notaId(), d.semanaId(), d.comissaoPercentual(),
                    d.devolvidos(), d.recibo(), d.visto(), d.dataDoAcerto()));
            case DadosDoPagamento d -> recibos.lancar(c, new NovoPagamento(d.id(), d.clienteId(), d.semanaId(), d.sequencia(),
                    d.formas(), d.pendenciaVista(), d.dataDoPagamento()));
            case DadosDoEstornoDoAcerto d -> acertos.estornar(c, new EstornoDoAcerto(d.id(), d.acertoId(), d.motivo()));
            case DadosDoEstornoDoRecibo d -> recibos.estornar(c, new EstornoDoRecibo(d.id(), d.reciboId(), d.motivo()));
            case Novo d -> clientes.criar(ctx.vendedorId(), ctx.usuarioId(), ctx.aparelho(), d);
            case DadosDaAlteracaoV2 d -> {
                clientes.alterar(ctx.vendedorId(), ctx.usuarioId(), ctx.aparelho(), d.clienteId(), d.campos());
                yield false;
            }
            default -> throw new IllegalStateException("tipo do consignado sem aplicação");
        };
        return new Aplicada(ja, horaDeclarada(dados) == null ? null : corrigida);
    }
}
