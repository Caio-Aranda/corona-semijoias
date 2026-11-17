import { useId, useState, type InputHTMLAttributes } from 'react';

export interface CampoProps extends InputHTMLAttributes<HTMLInputElement> {
  rotulo: string;
  /** Mensagem em português simples, dizendo o que fazer (glossário, parte 5). */
  erro?: string;
  ajuda?: string;
}

/** Campo de formulário com rótulo sempre visível e erro ligado ao campo. */
export function Campo({ rotulo, erro, ajuda, id, ...resto }: CampoProps) {
  const gerado = useId();
  const idCampo = id ?? gerado;
  const idAjuda = `${idCampo}-ajuda`;
  const idErro = `${idCampo}-erro`;
  const [visivel, setVisivel] = useState(false);
  const ehSenha = resto.type === 'password';
  const descricao = [ajuda ? idAjuda : '', erro ? idErro : ''].filter(Boolean).join(' ');
  return (
    <div className={`cs-campo${erro ? ' cs-campo--erro' : ''}`}>
      <label htmlFor={idCampo}>{rotulo}</label>
      {ehSenha ? (
        <div className="cs-campo__com-ver">
          <input
            {...resto}
            type={visivel ? 'text' : 'password'}
            id={idCampo}
            aria-invalid={erro ? true : undefined}
            aria-describedby={descricao || undefined}
          />
          <button
            type="button"
            className="cs-campo__ver"
            aria-pressed={visivel}
            onClick={() => setVisivel((v) => !v)}
          >
            <svg
              className="cs-campo__olho"
              viewBox="0 0 24 24"
              width="22"
              height="22"
              aria-hidden="true"
              focusable="false"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z" />
              <circle cx="12" cy="12" r="3" />
              {visivel ? <path d="M4 4l16 16" /> : null}
            </svg>
            {visivel ? 'Ocultar' : 'Mostrar'}
          </button>
        </div>
      ) : (
        <input
          {...resto}
          id={idCampo}
          aria-invalid={erro ? true : undefined}
          aria-describedby={descricao || undefined}
        />
      )}
      {ajuda ? (
        <span id={idAjuda} className="cs-campo__ajuda">
          {ajuda}
        </span>
      ) : null}
      {erro ? (
        <span id={idErro} role="alert" className="cs-campo__erro">
          {erro}
        </span>
      ) : null}
    </div>
  );
}
