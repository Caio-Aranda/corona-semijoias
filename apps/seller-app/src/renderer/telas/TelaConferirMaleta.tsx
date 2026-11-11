import { Aviso, Botao, Campo, JanelaDeConfirmacao, PainelDePecaLida, Tabela } from '@corona/ui';
import { useRef, useState, type FormEvent } from 'react';
import type { Conferencia, LinhaDaMaleta, Maleta } from '../../shared/dados';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, Dinheiro, novoId, useCotacao, useFoto } from '../chamadas';
import { TEXTOS } from '../textos';
import { BuscaPorNome } from './BuscaPorNome';
import { detalhesDaPeca } from './detalhes-da-peca';
import { PaginaDoVendedor } from './PaginaDoVendedor';

export interface TelaConferirMaletaProps {
  ponte: PonteDoCorona;
  maleta: Maleta;
  aoVoltar: () => void;
}

/**
 * Conferência da maleta (§7.1): o vendedor lê o código de cada peça recebida (cada leitura conta uma). O servidor compara
 * com a lista da maleta, grava a diferença e liga a maleta a este aparelho. Só se concluiu uma vez por maleta.
 */
export function TelaConferirMaleta({ ponte, maleta, aoVoltar }: TelaConferirMaletaProps) {
  const T = TEXTOS.conferencia;
  const { cotacao } = useCotacao(ponte);
  const [codigo, setCodigo] = useState('');
  const [contagem, setContagem] = useState<Record<string, number>>({});
  const [ultima, setUltima] = useState<LinhaDaMaleta | null>(null);
  const [resultado, setResultado] = useState<
    { tipo: 'sucesso' | 'erro'; texto: string } | undefined
  >();
  const [lendo, setLendo] = useState(false);
  const [confirmando, setConfirmando] = useState(false);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const [feita, setFeita] = useState<Conferencia | null>(null);
  // A mesma conferência reenviada (queda de internet) tem o mesmo id: o servidor não grava duas vezes.
  const idDaConferencia = useRef(novoId());
  const foto = useFoto(ponte, ultima?.produto.fotoHash, 'grande');

  const esperadas = maleta.itens.reduce((s, l) => s + l.saldo, 0);
  const contadas = Object.values(contagem).reduce((s, n) => s + n, 0);

  async function ler(evento: FormEvent) {
    evento.preventDefault();
    const lido = codigo.trim();
    if (!lido || lendo) return;
    setLendo(true);
    const r = await chamar<LinhaDaMaleta>(ponte, {
      operacao: 'maletas.peca',
      id: maleta.id,
      codigo: lido,
    });
    setLendo(false);
    setCodigo('');
    if (!r.ok) {
      setResultado({ tipo: 'erro', texto: r.mensagem });
      return;
    }
    contar(r.dados);
  }

  /** Peça lida pelo código ou escolhida pelo nome: conta uma peça do mesmo jeito. */
  function contar(linha: LinhaDaMaleta) {
    const agora = (contagem[linha.produto.id] ?? 0) + 1;
    setContagem({ ...contagem, [linha.produto.id]: agora });
    setUltima(linha);
    setResultado({ tipo: 'sucesso', texto: T.lida(linha.produto.nome, agora) });
  }

  async function concluir() {
    setEnviando(true);
    setErro(null);
    const itens = Object.entries(contagem).map(([produtoId, contada]) => ({ produtoId, contada }));
    const r = await chamar<Conferencia>(ponte, {
      operacao: 'maletas.conferir',
      id: maleta.id,
      conferenciaId: idDaConferencia.current,
      itens,
    });
    setEnviando(false);
    setConfirmando(false);
    if (r.ok) setFeita(r.dados);
    else setErro(r.mensagem);
  }

  if (feita) {
    return (
      <PaginaDoVendedor titulo={T.titulo} aoVoltar={aoVoltar}>
        <Aviso tipo={feita.divergencias === 0 ? 'sucesso' : 'atencao'} titulo={T.feita}>
          {feita.divergencias === 0 ? T.feitaTudoCerto : T.feitaComDiferenca(feita.divergencias)}
        </Aviso>
        <Tabela
          legenda={T.legenda}
          colunas={[
            { chave: 'peca', titulo: T.colunas.peca },
            { chave: 'esperado', titulo: T.colunas.esperado, numerica: true },
            { chave: 'contadas', titulo: T.colunas.contadas, numerica: true },
            { chave: 'diferenca', titulo: T.colunas.diferenca, numerica: true },
          ]}
          linhas={feita.itens.map((l) => ({
            peca: l.produtoNome,
            esperado: l.esperado,
            contadas: l.contado,
            diferenca: l.diferenca,
          }))}
        />
        <Botao variante="primario" grande onClick={aoVoltar}>
          {TEXTOS.voltar}
        </Botao>
      </PaginaDoVendedor>
    );
  }

  const faltam = maleta.itens.reduce(
    (s, l) => s + Math.max(0, l.saldo - (contagem[l.produto.id] ?? 0)),
    0,
  );
  const sobram = maleta.itens.reduce(
    (s, l) => s + Math.max(0, (contagem[l.produto.id] ?? 0) - l.saldo),
    0,
  );

  return (
    <PaginaDoVendedor titulo={T.titulo} aoVoltar={aoVoltar}>
      <p className="vendas-texto">{T.ajuda}</p>
      {erro ? <Aviso tipo="erro">{erro}</Aviso> : null}
      <form className="vendas-leitor" onSubmit={(e) => void ler(e)}>
        <Campo
          rotulo={T.campoCodigo}
          value={codigo}
          onChange={(e) => setCodigo(e.target.value)}
          autoFocus
          autoComplete="off"
          spellCheck={false}
        />
        <Botao type="submit" variante="primario" disabled={lendo || !codigo.trim()}>
          {T.lerAgora}
        </Botao>
      </form>
      {ultima ? (
        <PainelDePecaLida
          nome={ultima.produto.nome}
          codigo={ultima.produto.codigo ?? ''}
          preco={<Dinheiro reais={ultima.precoUnitario} cotacao={cotacao} />}
          fotoUrl={foto}
          detalhes={[
            {
              rotulo: TEXTOS.painelDaPeca.esperado,
              valor: TEXTOS.painelDaPeca.esperadoEContadas(
                ultima.saldo,
                contagem[ultima.produto.id] ?? 0,
              ),
            },
            ...detalhesDaPeca(ultima.produto),
          ]}
          {...(resultado ? { resultado } : {})}
        />
      ) : resultado ? (
        <Aviso tipo={resultado.tipo === 'erro' ? 'erro' : 'sucesso'}>{resultado.texto}</Aviso>
      ) : null}
      <BuscaPorNome ponte={ponte} maletaId={maleta.id} aoEscolher={contar} />
      {ultima && resultado?.tipo === 'erro' ? <Aviso tipo="erro">{resultado.texto}</Aviso> : null}
      <Tabela
        legenda={T.legenda}
        colunas={[
          { chave: 'peca', titulo: T.colunas.peca },
          { chave: 'esperado', titulo: T.colunas.esperado, numerica: true },
          { chave: 'contadas', titulo: T.colunas.contadas, numerica: true },
          { chave: 'diferenca', titulo: T.colunas.diferenca, numerica: true },
        ]}
        linhas={maleta.itens.map((l) => {
          const n = contagem[l.produto.id] ?? 0;
          return { peca: l.produto.nome, esperado: l.saldo, contadas: n, diferenca: n - l.saldo };
        })}
      />
      <Botao
        variante="primario"
        grande
        disabled={contadas === 0}
        onClick={() => setConfirmando(true)}
      >
        {T.concluir}
      </Botao>
      {confirmando ? (
        <JanelaDeConfirmacao
          titulo={T.janelaTitulo}
          rotuloConfirmar={T.concluir}
          ocupado={enviando}
          aoConfirmar={() => void concluir()}
          aoCancelar={() => setConfirmando(false)}
        >
          <p>{T.resumo(contadas, esperadas)}</p>
          <p>
            {faltam === 0 && sobram === 0
              ? T.tudoCerto
              : [faltam > 0 ? T.faltam(faltam) : '', sobram > 0 ? T.sobram(sobram) : '']
                  .filter(Boolean)
                  .join(' ')}
          </p>
          <p>{T.avisoSemVolta}</p>
        </JanelaDeConfirmacao>
      ) : null}
    </PaginaDoVendedor>
  );
}
