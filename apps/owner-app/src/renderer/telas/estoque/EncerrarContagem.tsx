import { Aviso, Campo, JanelaDeConfirmacao, Tabela } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, chamarTodasAsPaginas, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { precisaEscolherONaoLido, quantidadeComSinal, quantosAjustes } from '../../nucleo/estoque';
import { TEXTOS_DE_ESTOQUE } from '../../textos-estoque';
import { lerContagem, lerItem, lerPagina } from './dados';

const F = TEXTOS_DE_ESTOQUE.fechar;
const C = TEXTOS_DE_ESTOQUE.cancelarContagem;

export interface FecharContagemProps {
  ponte: PonteDoCorona;
  contagemId: string;
  /** A contagem foi fechada: devolve a frase de resultado. */
  aoFechar: (mensagem: string) => void;
  aoContinuar: () => void;
}

/**
 * Mostra os ajustes que o fechamento vai fazer, pergunta o que fazer com as peças não lidas
 * (zero ou como estão) e só então fecha. Sem escolher, o botão não deixa fechar.
 */
export function FecharContagem({ ponte, contagemId, aoFechar, aoContinuar }: FecharContagemProps) {
  const situacao = useBusca(
    () => chamar(ponte, 'contagens.ver', { id: contagemId }),
    `fechar|${contagemId}`,
  );
  const divergentes = useBusca(
    () =>
      chamarTodasAsPaginas(ponte, 'contagens.itens', {
        id: contagemId,
        filtro: 'DIVERGENTES',
      }),
    `fechar-itens|${contagemId}`,
  );
  const contagem = situacao.dados ? lerContagem(situacao.dados) : null;
  const itens = divergentes.dados ? lerPagina(divergentes.dados, lerItem) : null;
  const [zerar, setZerar] = useState<boolean | null>(null);
  const [tentou, setTentou] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const trava = useRef(false);

  const resumo = contagem?.resumo ?? null;
  const faltaEscolher = resumo ? precisaEscolherONaoLido(resumo, zerar) : false;
  const carregando =
    (situacao.carregando && contagem === null) || (divergentes.carregando && itens === null);
  const falhou = situacao.erro ?? divergentes.erro;

  async function fechar() {
    if (trava.current || !resumo) return;
    if (faltaEscolher) {
      setTentou(true);
      return;
    }
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'contagens.fechar', {
      id: contagemId,
      zerarNaoLidos: zerar === true,
    });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    const fechada = lerContagem(r.dados);
    const nomes = (fechada?.naoAjustadas ?? []).map((p) => p.nome);
    aoFechar(
      `${F.fechada(fechada?.ajustes ?? 0)}${nomes.length > 0 ? ` ${F.naoAjustadas(nomes)}` : ''}`,
    );
  }

  return (
    <JanelaDeConfirmacao
      titulo={F.titulo}
      rotuloConfirmar={F.confirmar}
      rotuloCancelar={F.continuar}
      ocupado={ocupado || carregando || !resumo}
      aoCancelar={aoContinuar}
      aoConfirmar={() => void fechar()}
    >
      <>
        {carregando ? <p role="status">{F.carregando}</p> : null}
        {falhou && !contagem ? (
          <AvisoDeErro erro={falhou} aoTentarDeNovo={situacao.recarregar} />
        ) : null}
        {resumo ? (
          <>
            <p>
              {resumo.comDiferenca === 0 && !zerar
                ? F.semAjustes
                : F.texto(quantosAjustes(resumo, zerar === true))}
            </p>
            {itens && itens.itens.length > 0 ? (
              <Tabela
                legenda={F.legenda}
                colunas={[
                  { chave: 'nome', titulo: TEXTOS_DE_ESTOQUE.comuns.peca },
                  {
                    chave: 'esperado',
                    titulo: TEXTOS_DE_ESTOQUE.contando.lista.colunas.esperado,
                    numerica: true,
                  },
                  {
                    chave: 'contado',
                    titulo: TEXTOS_DE_ESTOQUE.contando.lista.colunas.contado,
                    numerica: true,
                  },
                  {
                    chave: 'diferenca',
                    titulo: TEXTOS_DE_ESTOQUE.contando.lista.colunas.diferenca,
                    numerica: true,
                  },
                ]}
                linhas={itens.itens.map((i) => ({
                  nome: i.peca.nome,
                  esperado: i.esperado,
                  contado: i.contado ?? 0,
                  diferenca: quantidadeComSinal(i.diferenca ?? 0),
                }))}
              />
            ) : null}
            {resumo.naoLidos > 0 ? (
              <fieldset className="gestao-grupo">
                <legend>
                  {F.naoLidos(resumo.naoLidos)} {F.naoLidosPergunta}
                </legend>
                <label className="gestao-opcao">
                  <input
                    type="radio"
                    name="nao-lidos"
                    checked={zerar === true}
                    onChange={() => setZerar(true)}
                  />{' '}
                  {F.zero}
                </label>
                <label className="gestao-opcao">
                  <input
                    type="radio"
                    name="nao-lidos"
                    checked={zerar === false}
                    onChange={() => setZerar(false)}
                  />{' '}
                  {F.comoEstao}
                </label>
                {tentou && faltaEscolher ? <Aviso tipo="atencao">{F.escolhaAviso}</Aviso> : null}
              </fieldset>
            ) : null}
          </>
        ) : null}
        {erro ? <AvisoDeErro erro={erro} /> : null}
      </>
    </JanelaDeConfirmacao>
  );
}

export interface CancelarContagemProps {
  ponte: PonteDoCorona;
  contagemId: string;
  aoCancelar: (mensagem: string) => void;
  aoVoltar: () => void;
}

/** Cancela a contagem sem mexer no estoque. O motivo é opcional. */
export function CancelarContagem({
  ponte,
  contagemId,
  aoCancelar,
  aoVoltar,
}: CancelarContagemProps) {
  const [motivo, setMotivo] = useState('');
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const trava = useRef(false);

  async function cancelar() {
    if (trava.current) return;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'contagens.cancelar', {
      id: contagemId,
      ...(motivo.trim() ? { motivo: motivo.trim() } : {}),
    });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    aoCancelar(C.cancelada);
  }

  return (
    <JanelaDeConfirmacao
      titulo={C.titulo}
      rotuloConfirmar={C.confirmar}
      rotuloCancelar={C.voltar}
      perigosa
      ocupado={ocupado}
      aoCancelar={aoVoltar}
      aoConfirmar={() => void cancelar()}
    >
      <>
        {C.texto}
        <Campo
          rotulo={C.motivo}
          value={motivo}
          maxLength={500}
          autoComplete="off"
          onChange={(e) => setMotivo(e.target.value)}
        />
        {erro ? <AvisoDeErro erro={erro} /> : null}
      </>
    </JanelaDeConfirmacao>
  );
}
