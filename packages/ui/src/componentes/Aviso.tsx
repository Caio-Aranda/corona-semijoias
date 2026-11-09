import type { ReactNode } from 'react';

export interface AvisoProps {
  tipo?: 'informacao' | 'sucesso' | 'atencao' | 'erro';
  /** O que aconteceu, em uma frase curta. */
  titulo?: string;
  /** O que fazer (glossário, parte 5): "você", verbo no imperativo, sem termo técnico. */
  children: ReactNode;
}

/** Mensagem na tela. Erros avisam na hora (role "alert"); os demais, com calma (role "status"). */
export function Aviso({ tipo = 'informacao', titulo, children }: AvisoProps) {
  return (
    <div className={`cs-aviso cs-aviso--${tipo}`} role={tipo === 'erro' ? 'alert' : 'status'}>
      {titulo ? <p className="cs-aviso__titulo">{titulo}</p> : null}
      <p className="cs-aviso__texto">{children}</p>
    </div>
  );
}
