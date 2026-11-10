import { useEffect, useRef, type ReactNode } from 'react';
import { Botao, RodapeDeAcoes } from './Botao';

export interface JanelaDeConfirmacaoProps {
  titulo: string;
  /** Explica o que vai acontecer (glossário, parte 4: ações sem volta têm o nome da ação). */
  children: ReactNode;
  /** Nome da ação, ex.: "Desativar usuário". Nunca "OK" nem "Sim". */
  rotuloConfirmar: string;
  rotuloCancelar?: string;
  /** Ação que tira algo da pessoa: o botão fica vermelho. */
  perigosa?: boolean;
  ocupado?: boolean;
  aoConfirmar: () => void;
  aoCancelar: () => void;
}

/** Pergunta antes de uma ação séria. O foco começa em Cancelar; Esc também cancela. */
export function JanelaDeConfirmacao({
  titulo,
  children,
  rotuloConfirmar,
  rotuloCancelar = 'Cancelar',
  perigosa = false,
  ocupado = false,
  aoConfirmar,
  aoCancelar,
}: JanelaDeConfirmacaoProps) {
  const janela = useRef<HTMLDivElement>(null);
  useEffect(() => {
    // O primeiro botão do rodapé é o "Cancelar": errar o Enter nunca confirma a ação.
    janela.current?.querySelector<HTMLButtonElement>('.cs-rodape-acoes button')?.focus();
  }, []);
  return (
    <div
      className="cs-janela__fundo"
      onKeyDown={(e) => {
        if (e.key === 'Escape' && !ocupado) aoCancelar();
      }}
    >
      <div
        ref={janela}
        className="cs-janela"
        role="dialog"
        aria-modal="true"
        aria-labelledby="cs-janela-titulo"
      >
        <h2 id="cs-janela-titulo" className="cs-janela__titulo">
          {titulo}
        </h2>
        <div className="cs-janela__texto">{children}</div>
        <RodapeDeAcoes
          cancelar={
            <Botao onClick={aoCancelar} disabled={ocupado}>
              {rotuloCancelar}
            </Botao>
          }
          principal={
            <Botao
              variante={perigosa ? 'perigoso' : 'primario'}
              onClick={aoConfirmar}
              disabled={ocupado}
            >
              {rotuloConfirmar}
            </Botao>
          }
        />
      </div>
    </div>
  );
}
