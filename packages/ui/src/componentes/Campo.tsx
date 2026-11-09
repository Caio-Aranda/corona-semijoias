import { useId, type InputHTMLAttributes } from 'react';

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
  const descricao = [ajuda ? idAjuda : '', erro ? idErro : ''].filter(Boolean).join(' ');
  return (
    <div className={`cs-campo${erro ? ' cs-campo--erro' : ''}`}>
      <label htmlFor={idCampo}>{rotulo}</label>
      <input
        {...resto}
        id={idCampo}
        aria-invalid={erro ? true : undefined}
        aria-describedby={descricao || undefined}
      />
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
