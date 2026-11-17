import { Aviso, Botao } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, useBusca } from '../nucleo/chamadas';
import { TEXTOS } from '../textos';
import { TEXTOS_DE_ESTOQUE } from '../textos-estoque';
import { TEXTOS_DE_SINCRONIZACAO } from '../textos-sincronizacao';

const BOTOES = [
  'Painel da semana',
  'Montar maleta',
  'Receber compra',
  'Fazer acerto',
  'Cadastrar produto',
  'Relatórios',
] as const;

/** Botões que já levam a uma tela; os outros dizem "em breve" até a tarefa deles. */
const ROTA_DO_BOTAO: Partial<Record<(typeof BOTOES)[number], string>> = {
  'Painel da semana': 'painel/semana',
  'Receber compra': 'estoque/receber-compra',
  'Fazer acerto': 'acertos/lista',
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

/** Tela inicial da dona (§19.3): botões grandes e o bloco "O que precisa da sua atenção". */
export function TelaInicial({ ponte, nome }: TelaInicialProps) {
  const [emBreve, setEmBreve] = useState<string | null>(null);
  const avisos = useBusca(() => chamar(ponte, 'avisos.listar'), 'avisos');
  const lista = avisos.dados ? lerAvisos(avisos.dados) : null;

  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{nome ? `Olá, ${nome}.` : 'Olá.'}</h1>
      {emBreve ? (
        <Aviso tipo="informacao" titulo={TEXTOS.emBreve.titulo}>
          {TEXTOS.emBreve.texto(emBreve)}
        </Aviso>
      ) : null}
      <nav className="gestao-botoes" aria-label="O que você quer fazer?">
        {BOTOES.map((nomeDoBotao) => (
          <Botao
            key={nomeDoBotao}
            variante="primario"
            grande
            onClick={() => {
              const rota = ROTA_DO_BOTAO[nomeDoBotao];
              if (rota) window.location.hash = `/${rota}`;
              else setEmBreve(nomeDoBotao);
            }}
          >
            {nomeDoBotao}
          </Botao>
        ))}
      </nav>
      <section className="gestao-atencao" aria-labelledby="titulo-atencao">
        <h2 id="titulo-atencao">{TEXTOS.atencao.titulo}</h2>
        {avisos.erro && lista === null ? (
          <p>{avisos.erro.tipo === 'sem-conexao' ? avisos.erro.mensagem : TEXTOS.atencao.erro}</p>
        ) : null}
        {lista !== null && lista.length === 0 ? <p>{TEXTOS.atencao.tudoEmDia}</p> : null}
        {lista !== null && lista.length > 0 ? (
          <div className="gestao-avisos">
            {lista.map((a) => (
              <Aviso key={`${a.titulo}${a.texto}`} tipo="atencao" titulo={a.titulo}>
                {a.texto}
                {ROTA_DO_AVISO[a.tipo] ? (
                  <Botao
                    onClick={() => {
                      window.location.hash = `/${ROTA_DO_AVISO[a.tipo]}`;
                    }}
                  >
                    {TEXTOS_DE_SINCRONIZACAO.abrir}
                  </Botao>
                ) : null}
                {/acabando/i.test(a.titulo) ? (
                  <Botao
                    onClick={() => {
                      window.location.hash = '/estoque/acabando';
                    }}
                  >
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
