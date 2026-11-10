package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.arquivos.ArmazenamentoDeArquivos;
import br.com.coronasemijoias.api.arquivos.PropriedadesDeArquivos;
import br.com.coronasemijoias.api.arquivos.TipoDeArquivo;
import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.cadastros.ServicoDeCadastro;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Documentos do vendedor (aba "Documentos"). O conteúdo vai para o {@link ArmazenamentoDeArquivos};
 * o banco guarda só os dados e a chave. Vale o tipo do conteúdo (PDF, JPG, PNG ou WebP), não o que o
 * navegador disse, e o arquivo tem no máximo o tamanho configurado (10 MB). Excluir é lógico: o
 * documento some da lista e do download, mas o arquivo fica guardado.
 */
@Service
public class ServicoDeDocumentos {

    static final String AREA = "vendedores";
    private static final int TAMANHO_MAXIMO_DO_NOME = 200;

    /** Um documento e o conteúdo dele, para o download. */
    public record Baixado(DocumentoDoVendedor documento, byte[] conteudo) {
    }

    private final ServicoDeVendedores vendedores;
    private final RepositorioDeDocumentos documentos;
    private final ArmazenamentoDeArquivos armazenamento;
    private final PropriedadesDeArquivos propriedades;
    private final Clock relogio;

    public ServicoDeDocumentos(ServicoDeVendedores vendedores, RepositorioDeDocumentos documentos,
            ArmazenamentoDeArquivos armazenamento, PropriedadesDeArquivos propriedades, Clock relogio) {
        this.vendedores = vendedores;
        this.documentos = documentos;
        this.armazenamento = armazenamento;
        this.propriedades = propriedades;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    public List<DocumentoDoVendedor.Resposta> listar(UUID vendedorId) {
        vendedores.obter(vendedorId);
        return documentos.findByVendedorIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(vendedorId).stream()
                .map(DocumentoDoVendedor::resposta).toList();
    }

    @Transactional
    public DocumentoDoVendedor.Resposta enviar(UUID vendedorId, String descricao, MultipartFile arquivo,
            UUID enviadoPor) {
        vendedores.obter(vendedorId);
        String descricaoLimpa = Validacoes.limpar(descricao);
        if (descricaoLimpa == null) {
            throw Validacoes.recusar("descricao", Mensagens.OBRIGATORIO);
        }
        if (descricaoLimpa.length() > 200) {
            throw Validacoes.recusar("descricao", "Use no máximo 200 caracteres.");
        }
        if (arquivo == null || arquivo.isEmpty()) {
            throw Validacoes.recusar("arquivo", "Este arquivo está vazio. Escolha outro.");
        }
        if (arquivo.getSize() > propriedades.tamanhoMaximo().toBytes()) {
            throw new ErroDaApi(HttpStatus.PAYLOAD_TOO_LARGE, CodigoErro.ARQUIVO_GRANDE_DEMAIS);
        }
        byte[] conteudo = bytesDe(arquivo);
        TipoDeArquivo tipo = TipoDeArquivo.identificar(conteudo)
                .orElseThrow(() -> new ErroDaApi(HttpStatus.UNSUPPORTED_MEDIA_TYPE, CodigoErro.ARQUIVO_TIPO_NAO_ACEITO));

        String chave = armazenamento.guardar(AREA, conteudo, tipo.extensao());
        DocumentoDoVendedor documento = new DocumentoDoVendedor(vendedorId, descricaoLimpa,
                nomeSeguro(arquivo.getOriginalFilename(), tipo), tipo.tipoDeConteudo(), conteudo.length,
                sha256(conteudo), chave, enviadoPor);
        return documentos.saveAndFlush(documento).resposta();
    }

    @Transactional(readOnly = true)
    public Baixado baixar(UUID vendedorId, UUID id) {
        DocumentoDoVendedor documento = procurar(vendedorId, id);
        return new Baixado(documento, armazenamento.ler(documento.getChaveArquivo()));
    }

    @Transactional
    public void excluir(UUID vendedorId, UUID id) {
        DocumentoDoVendedor documento = procurar(vendedorId, id);
        documento.marcarComoExcluida(relogio.instant());
        documentos.saveAndFlush(documento);
    }

    private DocumentoDoVendedor procurar(UUID vendedorId, UUID id) {
        vendedores.obter(vendedorId);
        return documentos.findById(id)
                .filter(d -> d.getVendedorId().equals(vendedorId) && !d.estaExcluida())
                .orElseThrow(ServicoDeCadastro::naoEncontrado);
    }

    private static byte[] bytesDe(MultipartFile arquivo) {
        try {
            return arquivo.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler o arquivo enviado", e);
        }
    }

    /** Só o nome do arquivo (sem pasta), sem caracteres de controle e no máximo 200 caracteres. */
    static String nomeSeguro(String enviado, TipoDeArquivo tipo) {
        String nome = enviado == null ? "" : enviado.replace('\\', '/');
        nome = nome.substring(nome.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (nome.isEmpty()) {
            nome = "documento." + tipo.extensao();
        }
        return nome.length() > TAMANHO_MAXIMO_DO_NOME ? nome.substring(0, TAMANHO_MAXIMO_DO_NOME) : nome;
    }

    private static String sha256(byte[] conteudo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 sempre existe na JVM", e);
        }
    }
}
