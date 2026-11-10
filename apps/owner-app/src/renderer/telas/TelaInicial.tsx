import { Aviso, Botao } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, useBusca } from '../nucleo/chamadas';
import { TEXTOS } from '../textos';

const BOTOES = [
  'Montar maleta',
  'Receber compra',
  'Fazer acerto',
  'Cadastrar produto',
  'Relatórios',
] as const;

export interface TelaInicialProps {
  ponte: PonteDoCorona;
  /** Nome da pessoa, quando já se sabe. */
  nome: string | null;
}

interface AvisoDaDona {
  titulo: string;
  texto: string;
}

function lerAvisos(bruto: unknown): AvisoDaDona[] {
  const lista = (bruto as { avisos?: unknown } | null)?.avisos;
  if (!Array.isArray(lista)) return [];
  return lista.flatMap((a: unknown) => {
    const { titulo, texto } = (a ?? {}) as { titulo?: unknown; texto?: unknown };
    return typeof titulo === 'string' && typeof texto === 'string' ? [{ titulo, texto }] : [];
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
            onClick={() => setEmBreve(nomeDoBotao)}
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
              </Aviso>
            ))}
          </div>
        ) : null}
      </section>
    </div>
  );
}
