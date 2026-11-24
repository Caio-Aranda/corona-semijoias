import { Aviso, Botao } from '@corona/ui';
import { avisoEmEspanhol } from '../idioma/es/avisos-da-dona';
import { useState } from 'react';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, useBusca } from '../nucleo/chamadas';
import { idiomaAtual, localeAtual } from '../idioma/idioma';
import { useCotacao } from '../nucleo/cotacao';
import { guaranisPorReal, reaisPorDolar } from '../nucleo/cotacao-na-tela';
import { dolarNaTela, lerPainel, reaisNaTela } from '../nucleo/painel-semanal';
import { TEXTOS } from '../textos';
import { TEXTOS_DE_ESTOQUE } from '../textos-estoque';
import { TEXTOS_DE_SINCRONIZACAO } from '../textos-sincronizacao';
import { TEXTOS_DA_INICIAL as I } from '../textos-inicial';
import { TEXTOS_DO_PAINEL } from '../textos-painel';
import { useTelasDoGestao } from '../nucleo/telas-do-gestao';
import { IconeDoAtalho } from './IconeDoAtalho';
import { rotaDaArea } from '../nucleo/areas-de-relatorios';

const BOTOES = ['painel', 'montar', 'receber', 'acerto', 'cadastrar', 'relatorios'] as const;

/**
 * A tela de cada atalho. Montar maleta, Cadastrar produto e Relatórios ficaram em "em breve" desde a tarefa 2.6, mesmo
 * depois de as telas existirem (revisão de 23/11/2026). Relatórios abre a área de vendas, a dos números da loja.
 */
const ROTA_DO_BOTAO: Partial<Record<(typeof BOTOES)[number], string>> = {
  painel: 'painel/semana',
  montar: 'maletas/montar',
  receber: 'estoque/receber-compra',
  acerto: 'acertos/lista',
  cadastrar: 'cadastros/produtos',
  relatorios: rotaDaArea('vendas'),
};

export interface TelaInicialProps {
  ponte: PonteDoCorona;
  /** Nome da pessoa, quando já se sabe. */
  nome: string | null;
}

interface AvisoDaDona {
  titulo: string;
  texto: string;
  tipo: string;
}

/** Para onde o botão "Abrir" de cada aviso leva (tarefa 5.10). */
const ROTA_DO_AVISO: Record<string, string> = {
  ENVIOS_COM_PROBLEMA: 'computadores/envios',
  VENDAS_DE_COMPUTADOR_BLOQUEADO: 'computadores/vendas',
  COMPUTADOR_SEM_ENVIAR: 'computadores/lista',
  COMPUTADOR_NOVO: 'computadores/lista',
  DEVOLUCOES_PARA_AUTORIZAR: 'devolucoes/autorizar',
  DEVOLUCOES_PARA_REVISAR: 'devolucoes/revisar',
  MUITAS_DEVOLUCOES: 'devolucoes/lista',
  CONFERENCIA_COM_DIFERENCA: 'conciliacoes/conferencia-diaria',
  CONFERENCIA_NAO_RODOU: 'conciliacoes/conferencia-diaria',
};

function lerAvisos(bruto: unknown): AvisoDaDona[] {
  const lista = (bruto as { avisos?: unknown } | null)?.avisos;
  if (!Array.isArray(lista)) return [];
  return lista.flatMap((a: unknown) => {
    const { titulo, texto, tipo } = (a ?? {}) as {
      titulo?: unknown;
      texto?: unknown;
      tipo?: unknown;
    };
    return typeof titulo === 'string' && typeof texto === 'string'
      ? [{ titulo, texto, tipo: typeof tipo === 'string' ? tipo : '' }]
      : [];
  });
}

/** Aviso que pede ação rápida fica vermelho; o resto é atenção ou informação. */
function tipoDoAviso(tipo: string): 'erro' | 'atencao' | 'informacao' {
  if (/PROBLEMA|BLOQUEADO|DIFERENCA|MUITAS/.test(tipo)) return 'erro';
  if (/NOVO/.test(tipo)) return 'informacao';
  return 'atencao';
}

function irPara(rota: string) {
  window.location.hash = `/${rota}`;
}

function CartaoDeNumero({
  titulo,
  numero,
  detalhe,
  rota,
}: {
  titulo: string;
  numero: string;
  detalhe?: string | undefined;
  rota: string;
}) {
  return (
    <button type="button" className="gestao-numero" onClick={() => irPara(rota)}>
      <span className="gestao-numero__titulo">{titulo}</span>
      <span className="gestao-numero__valor">{numero}</span>
      {detalhe ? <span className="gestao-numero__detalhe">{detalhe}</span> : null}
    </button>
  );
}

/** Tela inicial da dona (§19.3): saudação, atalhos, resumo da semana e o bloco "O que precisa da sua atenção". */
export function TelaInicial({ ponte, nome }: TelaInicialProps) {
  const [emBreve, setEmBreve] = useState<string | null>(null);
  const avisos = useBusca(() => chamar(ponte, 'avisos.listar'), 'avisos');
  const lista = avisos.dados
    ? lerAvisos(avisos.dados).map((a) => (idiomaAtual() === 'es' ? avisoEmEspanhol(a) : a))
    : null;
  const painelBusca = useBusca(() => chamar(ponte, 'painel.semanal'), 'painel-da-inicial');
  const painel = painelBusca.dados ? lerPainel(painelBusca.dados) : null;
  const { taxas } = useCotacao();
  // O acerto antigo da §7.4 fica escondido (ADR-0016, item 15): sem o botão e sem o cartão de acertos.
  const { acertoDoVendedorVisivel } = useTelasDoGestao();
  const botoes = BOTOES.filter((b) => b !== 'acerto' || acertoDoVendedorVisivel);

  const quantidade = (chave: string, rotulo: string): number => {
    const cartao = painel?.cartoes.find((c) => c.chave === chave);
    return cartao?.itens.find((i) => i.rotulo === rotulo)?.quantidade ?? 0;
  };
  const maletas =
    painel === null
      ? null
      : quantidade('novasMaletas', TEXTOS_DO_PAINEL.itens.maletas) +
        quantidade('reposicoes', TEXTOS_DO_PAINEL.itens.maletas);
  const acertos = painel === null ? null : quantidade('acerto', TEXTOS_DO_PAINEL.itens.acertos);
  const vendido = painel?.total.vendido ?? null;
  const traco = '—';

  const hoje = new Date().toLocaleDateString(localeAtual(), {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });

  return (
    <div className="gestao-tela">
      <header className="gestao-saudacao">
        <div>
          <h1 className="gestao-titulo">{I.saudacao(nome)}</h1>
          <p className="gestao-data">{hoje}</p>
        </div>
        <div className="gestao-cotacao-dia" aria-label={I.cotacao.rotulo}>
          {taxas ? (
            <>
              <span>
                <b>{I.cotacao.dolar}</b> {reaisPorDolar(taxas.dolar)}
              </span>
              <span>
                <b>{I.cotacao.guarani}</b> {guaranisPorReal(taxas.guarani)}
              </span>
            </>
          ) : (
            <span>{I.cotacao.semCotacao}</span>
          )}
          <Botao onClick={() => irPara('financeiro/cotacao')}>{I.cotacao.mudar}</Botao>
        </div>
      </header>
      {emBreve ? (
        <Aviso tipo="informacao" titulo={TEXTOS.emBreve.titulo}>
          {TEXTOS.emBreve.texto(emBreve)}
        </Aviso>
      ) : null}
      <nav className="gestao-atalhos" aria-label={I.oQueFazer}>
        {botoes.map((nomeDoBotao) => (
          <button
            key={nomeDoBotao}
            type="button"
            className="gestao-atalho"
            aria-label={I.atalhos[nomeDoBotao].nome}
            onClick={() => {
              const rota = ROTA_DO_BOTAO[nomeDoBotao];
              if (rota) irPara(rota);
              else setEmBreve(I.atalhos[nomeDoBotao].nome);
            }}
          >
            <span className="gestao-atalho__icone">
              <IconeDoAtalho nome={nomeDoBotao} />
            </span>
            <span className="gestao-atalho__nome">{I.atalhos[nomeDoBotao].nome}</span>
            <span className="gestao-atalho__ajuda">{I.atalhos[nomeDoBotao].ajuda}</span>
          </button>
        ))}
      </nav>
      <section aria-labelledby="titulo-resumo">
        <h2 id="titulo-resumo" className="gestao-secao">
          {I.resumo.titulo}
        </h2>
        <div className="gestao-numeros">
          <CartaoDeNumero
            titulo={I.resumo.vendido}
            numero={vendido?.real ? reaisNaTela(vendido.real) : traco}
            detalhe={vendido?.dolar ? dolarNaTela(vendido.dolar) : undefined}
            rota="painel/semana"
          />
          <CartaoDeNumero
            titulo={I.resumo.maletas}
            numero={maletas === null ? traco : String(maletas)}
            rota="maletas/lista"
          />
          {acertoDoVendedorVisivel ? (
            <CartaoDeNumero
              titulo={I.resumo.acertos}
              numero={acertos === null ? traco : String(acertos)}
              rota="acertos/lista"
            />
          ) : null}
          <CartaoDeNumero
            titulo={I.resumo.avisos}
            numero={lista === null ? traco : String(lista.length)}
            rota="inicio"
          />
        </div>
      </section>
      <section className="gestao-atencao" aria-labelledby="titulo-atencao">
        <h2 id="titulo-atencao" className="gestao-secao">
          {TEXTOS.atencao.titulo}
        </h2>
        {avisos.erro && lista === null ? (
          <p>{avisos.erro.tipo === 'sem-conexao' ? avisos.erro.mensagem : TEXTOS.atencao.erro}</p>
        ) : null}
        {lista !== null && lista.length === 0 ? (
          <div className="gestao-tudo-em-dia">
            <span className="gestao-tudo-em-dia__icone" aria-hidden="true">
              ✓
            </span>
            <div>
              <p className="gestao-tudo-em-dia__titulo">{TEXTOS.atencao.tudoEmDia}</p>
              <p>{I.tranquilo}</p>
            </div>
          </div>
        ) : null}
        {lista !== null && lista.length > 0 ? (
          <div className="gestao-avisos">
            {lista.map((a) => (
              <Aviso key={`${a.titulo}${a.texto}`} tipo={tipoDoAviso(a.tipo)} titulo={a.titulo}>
                {a.texto}
                {ROTA_DO_AVISO[a.tipo] ? (
                  <Botao onClick={() => irPara(ROTA_DO_AVISO[a.tipo] ?? '')}>
                    {TEXTOS_DE_SINCRONIZACAO.abrir}
                  </Botao>
                ) : null}
                {/acabando/i.test(a.titulo) ? (
                  <Botao onClick={() => irPara('estoque/acabando')}>
                    {TEXTOS_DE_ESTOQUE.aviso.verLista}
                  </Botao>
                ) : null}
              </Aviso>
            ))}
          </div>
        ) : null}
      </section>
    </div>
  );
}
