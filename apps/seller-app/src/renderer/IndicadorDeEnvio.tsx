import { Aviso, Botao } from '@corona/ui';
import { useCallback, useEffect, useState } from 'react';
import type { EnvioNaoAceito } from '../shared/dados';
import type { EstadoDaSincronizacao, PonteDoCorona } from '../shared/ponte';
import { textoDoPrazo } from '../shared/prazo';
import { chamar } from './chamadas';
import { idiomaAtual, LOCAL_DO_IDIOMA } from './idioma';
import { TEXTOS } from './textos';

/** O estado do envio e do recebimento, já ao assinar e sempre que mudar. Só contadores, sim/não e datas. */
export function useEstadoDeEnvio(ponte: PonteDoCorona): EstadoDaSincronizacao | null {
  const [estado, setEstado] = useState<EstadoDaSincronizacao | null>(null);
  useEffect(() => ponte.aoMudarASincronizacao(setEstado), [ponte]);
  return estado;
}

/** "09/11 às 14:30" (ou "09/11 a las 14:30"), no horário de Brasília. */
export function dataEHoraParaTela(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const partes = new Intl.DateTimeFormat(LOCAL_DO_IDIOMA[idiomaAtual()], {
    timeZone: 'America/Sao_Paulo',
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(d);
  const p = (tipo: string) => partes.find((x) => x.type === tipo)?.value ?? '';
  return TEXTOS.envio.diaEHora(`${p('day')}/${p('month')}`, `${p('hour')}:${p('minute')}`);
}

/** O que está esperando para ser enviado, em palavras comuns; vazio se nada espera. */
export function textoDoQueAguarda(e: EstadoDaSincronizacao): string | null {
  if (e.aguardando === 0) return null;
  return e.aguardando === e.vendasAguardando
    ? TEXTOS.envio.vendasAguardando(e.aguardando)
    : TEXTOS.envio.itensAguardando(e.aguardando);
}

/** "Baixando fotos: 45 de 120" enquanto faltam fotos das maletas; vazio quando todas já estão no computador. */
export function textoDasFotos(e: EstadoDaSincronizacao): string | null {
  if (e.fotosTotal === 0 || e.fotosBaixadas >= e.fotosTotal) return null;
  return TEXTOS.envio.baixandoFotos(e.fotosBaixadas, e.fotosTotal);
}

/** A frase principal do indicador (glossário, parte 7). `null` enquanto ainda não se sabe nada. */
export function textoDoIndicador(e: EstadoDaSincronizacao): string | null {
  const T = TEXTOS.envio;
  if (e.comInternet === false) return T.semInternet;
  if (e.comInternet === true && e.enviando) return T.enviando;
  if (e.comInternet === true && e.aguardando === 0) return T.tudoEnviado;
  return null;
}

/**
 * O indicador do alto das telas de trabalho: com internet ou sem, o que espera para ser enviado e, se o servidor não
 * aceitou algum envio, a lista com o motivo e o botão "Entendi" (tarefa 5.7; ADR-0013, item 18).
 */
export function IndicadorDeEnvio({ ponte }: { ponte: PonteDoCorona }) {
  const T = TEXTOS.envio;
  const estado = useEstadoDeEnvio(ponte);
  const [lista, setLista] = useState<EnvioNaoAceito[]>([]);
  const recusadas = estado?.recusadas ?? 0;

  const carregar = useCallback(async () => {
    if (recusadas === 0) {
      setLista([]);
      return;
    }
    const r = await chamar<EnvioNaoAceito[]>(ponte, { operacao: 'envios.naoAceitos' });
    if (r.ok && Array.isArray(r.dados)) setLista(r.dados);
  }, [ponte, recusadas]);

  useEffect(() => {
    void carregar();
  }, [carregar]);

  async function entendi(id: string) {
    // A linha some na hora; o motor avisa o novo estado em seguida.
    setLista((atual) => atual.filter((e) => e.id !== id));
    await chamar(ponte, { operacao: 'envios.entendi', id });
  }

  if (!estado) return null;
  const principal = textoDoIndicador(estado);
  const aguarda = textoDoQueAguarda(estado);
  const fotos = textoDasFotos(estado);
  const aviso = textoDoPrazo(estado.prazo, idiomaAtual());
  return (
    <section className="vendas-indicador" aria-label={T.area}>
      {principal ? (
        <p className="vendas-texto" role="status">
          {principal}
        </p>
      ) : null}
      {aguarda ? <p className="vendas-texto">{aguarda}</p> : null}
      {fotos ? (
        <p className="vendas-texto" data-testid="baixando-fotos">
          {fotos}
        </p>
      ) : null}
      {aviso ? (
        <Aviso tipo="atencao" titulo={aviso.titulo}>
          {aviso.texto}
        </Aviso>
      ) : null}
      {estado.appDesatualizado ? (
        <Aviso tipo="atencao" titulo={T.desatualizadoTitulo}>
          {T.desatualizado}
        </Aviso>
      ) : null}
      {estado.naoConseguiuEnviar ? <Aviso tipo="atencao">{T.naoConseguiu}</Aviso> : null}
      {lista.length > 0 ? (
        <Aviso
          tipo="atencao"
          titulo={T.naoAceitas(
            lista.length,
            lista.every((e) => e.ehVenda),
          )}
        >
          <h2 className="vendas-subtitulo">{T.tituloDaLista}</h2>
          <ul className="vendas-lista">
            {lista.map((e) => (
              <li key={e.id}>
                <span>
                  <strong>{e.descricao}.</strong> {T.feitoEm(dataEHoraParaTela(e.feitoEm))}.{' '}
                  {T.motivo}: {e.motivo}
                </span>
                <Botao aria-label={T.entendiDe(e.descricao)} onClick={() => void entendi(e.id)}>
                  {T.entendi}
                </Botao>
              </li>
            ))}
          </ul>
        </Aviso>
      ) : null}
    </section>
  );
}

/**
 * "Dados até 09/11 às 14:30. Cotação de 06/11/2026. 3 vendas aguardando envio" (glossário, parte 7): vai nos relatórios
 * e nas listas que o vendedor lê do computador.
 */
export function AvisoDosDados({
  ponte,
  cotacao,
}: {
  ponte: PonteDoCorona;
  /** A frase da cotação ("Cotação de 06/11/2026."), quando houver. */
  cotacao?: string | null;
}) {
  const estado = useEstadoDeEnvio(ponte);
  if (!estado) return null;
  const aguarda = textoDoQueAguarda(estado);
  return (
    <p className="vendas-texto vendas-texto--pequeno" data-testid="dados-ate">
      {estado.dadosAte
        ? TEXTOS.envio.dadosAte(dataEHoraParaTela(estado.dadosAte))
        : TEXTOS.envio.semDados}
      {cotacao ? ` ${cotacao}` : ''}
      {aguarda ? ` ${aguarda}` : ''}
    </p>
  );
}
