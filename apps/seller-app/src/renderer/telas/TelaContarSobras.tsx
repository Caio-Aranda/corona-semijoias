import { Aviso, Botao } from '@corona/ui';
import { Campo, PainelDePecaLida, Tabela } from '../componentes-do-vendedor';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { FichaDoAcertoAgora, LinhaDaMaleta } from '../../shared/dados';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, Dinheiro, novoId, useCotacao, useFoto } from '../chamadas';
import { TEXTOS } from '../textos';
import { detalhesDaPeca } from './detalhes-da-peca';
import { PaginaDoVendedor } from './PaginaDoVendedor';

export interface TelaContarSobrasProps {
  ponte: PonteDoCorona;
  acertoId: string;
  aoVoltar: () => void;
}

type Estado = 'BOA' | 'DEFEITO';
type Resultado = { tipo: 'sucesso' | 'erro'; texto: string };

/**
 * Contar as sobras (tarefa 8.10; §7.4): o vendedor lê o código de cada peça que sobrou, como boa ou com defeito, e pode
 * desfazer uma leitura. **Só com internet**: cada leitura vai direto ao servidor (nada fica guardado nem em fila) e a
 * contagem na tela é a que o servidor devolve. A leitura repetida por queda de internet leva o mesmo UUID, e o servidor não
 * conta duas vezes. A tela não fecha nem desiste do acerto (só a dona) e não calcula nada.
 */
export function TelaContarSobras({ ponte, acertoId, aoVoltar }: TelaContarSobrasProps) {
  const T = TEXTOS.acerto;
  const { cotacao } = useCotacao(ponte);
  const [ficha, setFicha] = useState<FichaDoAcertoAgora | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [codigo, setCodigo] = useState('');
  const [estado, setEstado] = useState<Estado>('BOA');
  const [ocupado, setOcupado] = useState(false);
  const [ultima, setUltima] = useState<LinhaDaMaleta | null>(null);
  const [resultado, setResultado] = useState<Resultado | undefined>();
  // A mesma leitura reenviada (queda de internet) tem o mesmo id: o servidor não conta duas vezes.
  const pendente = useRef<{ chave: string; id: string } | null>(null);
  const foto = useFoto(ponte, ultima?.produto.fotoHash, 'grande');

  const textoDaFalha = (mensagem: string) =>
    mensagem === TEXTOS.semInternet.curto ? T.contarSemInternet : mensagem;

  useEffect(() => {
    void chamar<FichaDoAcertoAgora>(ponte, { operacao: 'acertos.agora', id: acertoId }).then(
      (r) => {
        if (r.ok) setFicha(r.dados);
        else setErro(r.mensagem === TEXTOS.semInternet.curto ? T.contarSemInternet : r.mensagem);
      },
    );
    // Só ao abrir a tela.
  }, [ponte, acertoId, T.contarSemInternet]);

  /** Manda uma leitura (1 para ler, -1 para desfazer) e guarda a contagem que o servidor devolveu. */
  async function enviar(produtoId: string, estadoPeca: Estado, quantidade: number) {
    const chave = `${produtoId}|${estadoPeca}|${quantidade}`;
    const id = pendente.current?.chave === chave ? pendente.current.id : novoId();
    pendente.current = { chave, id };
    const r = await chamar<FichaDoAcertoAgora>(ponte, {
      operacao: 'acertos.ler',
      id: acertoId,
      leitura: { id, produtoId, quantidade, estadoPeca },
    });
    if (!r.ok) {
      setResultado({ tipo: 'erro', texto: textoDaFalha(r.mensagem) });
      return null;
    }
    pendente.current = null;
    setFicha(r.dados);
    return r.dados;
  }

  /** Acha a peça pelo código em alguma das maletas do acerto (a conferência da maleta usa a mesma consulta). */
  async function acharPeca(lido: string): Promise<LinhaDaMaleta | string> {
    let motivo: string = T.pecaDeFora;
    for (const m of ficha?.maletas ?? []) {
      const r = await chamar<LinhaDaMaleta>(ponte, {
        operacao: 'maletas.peca',
        id: m.id,
        codigo: lido,
      });
      if (r.ok) return r.dados;
      motivo = r.mensagem;
    }
    return motivo;
  }

  async function ler(evento: FormEvent) {
    evento.preventDefault();
    const lido = codigo.trim();
    if (!lido || ocupado) return;
    setOcupado(true);
    setResultado(undefined);
    const peca = await acharPeca(lido);
    if (typeof peca === 'string') {
      setResultado({ tipo: 'erro', texto: peca });
    } else {
      setCodigo('');
      const nova = await enviar(peca.produto.id, estado, 1);
      if (nova) {
        setUltima(peca);
        const n = nova.contagem
          .filter((c) => c.produto.id === peca.produto.id)
          .reduce((s, c) => s + c.quantidade, 0);
        setResultado({ tipo: 'sucesso', texto: T.lida(peca.produto.nome, n) });
      }
    }
    setOcupado(false);
  }

  async function desfazer(produtoId: string, nome: string, estadoPeca: Estado) {
    if (ocupado) return;
    setOcupado(true);
    setResultado(undefined);
    const nova = await enviar(produtoId, estadoPeca, -1);
    if (nova) setResultado({ tipo: 'sucesso', texto: T.desfeita(nome) });
    setOcupado(false);
  }

  if (!ficha) {
    return (
      <PaginaDoVendedor ponte={ponte} titulo={T.contarTitulo} aoVoltar={aoVoltar}>
        {erro ? (
          <Aviso tipo="informacao">{erro}</Aviso>
        ) : (
          <p className="vendas-texto" aria-busy="true">
            {T.textoCarregando}
          </p>
        )}
      </PaginaDoVendedor>
    );
  }

  const emAndamento = ficha.status === 'EM_ACERTO';
  const contadasDaUltima = ultima
    ? ficha.contagem
        .filter((c) => c.produto.id === ultima.produto.id)
        .reduce((s, c) => s + c.quantidade, 0)
    : 0;

  return (
    <PaginaDoVendedor ponte={ponte} titulo={T.contarTitulo} aoVoltar={aoVoltar}>
      <p className="vendas-texto vendas-texto--pequeno">{T.contagemDaSemana(ficha.semanaNumero)}</p>
      {!emAndamento ? <Aviso tipo="informacao">{T.encerrado}</Aviso> : null}
      {emAndamento ? (
        <>
          <p className="vendas-texto">{T.contarAjuda}</p>
          <fieldset className="vendas-estados">
            <legend>{T.estadoDaPeca}</legend>
            {(['BOA', 'DEFEITO'] as const).map((e) => (
              <label key={e} className="vendas-opcao">
                <input
                  type="radio"
                  name="estado-da-peca"
                  checked={estado === e}
                  onChange={() => setEstado(e)}
                />{' '}
                {T.estados[e]}
              </label>
            ))}
          </fieldset>
          <form className="vendas-leitor" onSubmit={(e) => void ler(e)}>
            <Campo
              rotulo={T.campoCodigo}
              value={codigo}
              onChange={(e) => setCodigo(e.target.value)}
              autoFocus
              autoComplete="off"
              spellCheck={false}
            />
            <Botao type="submit" variante="primario" disabled={ocupado || !codigo.trim()}>
              {T.lerAgora}
            </Botao>
          </form>
        </>
      ) : null}
      {ultima ? (
        <PainelDePecaLida
          nome={ultima.produto.nome}
          codigo={ultima.produto.codigo ?? ''}
          preco={<Dinheiro reais={ultima.precoUnitario} cotacao={cotacao} />}
          fotoUrl={foto}
          detalhes={[
            {
              rotulo: TEXTOS.painelDaPeca.esperado,
              valor: TEXTOS.painelDaPeca.esperadoEContadas(ultima.saldo, contadasDaUltima),
            },
            ...detalhesDaPeca(ultima.produto),
          ]}
          {...(resultado ? { resultado } : {})}
        />
      ) : resultado ? (
        <Aviso tipo={resultado.tipo === 'erro' ? 'erro' : 'sucesso'}>{resultado.texto}</Aviso>
      ) : null}
      {ultima && resultado?.tipo === 'erro' ? <Aviso tipo="erro">{resultado.texto}</Aviso> : null}
      {ficha.contagem.length === 0 ? (
        <p className="vendas-texto">{T.nenhumaLida}</p>
      ) : (
        <Tabela
          legenda={T.contagemLegenda}
          colunas={[
            { chave: 'peca', titulo: T.contagemColunas.peca },
            { chave: 'estado', titulo: T.contagemColunas.estado },
            { chave: 'contadas', titulo: T.contagemColunas.contadas, numerica: true },
            { chave: 'acao', titulo: T.contagemColunas.acao },
          ]}
          linhas={ficha.contagem.map((c) => ({
            peca: c.produto.nome,
            estado: T.estados[c.estadoPeca] ?? c.estadoPeca,
            contadas: c.quantidade,
            acao: emAndamento ? (
              <Botao
                aria-label={T.desfazerDe(c.produto.nome)}
                disabled={ocupado}
                onClick={() => void desfazer(c.produto.id, c.produto.nome, c.estadoPeca)}
              >
                {T.desfazer}
              </Botao>
            ) : (
              ''
            ),
          }))}
        />
      )}
    </PaginaDoVendedor>
  );
}
