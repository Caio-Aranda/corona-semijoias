import { Aviso } from '@corona/ui';
import { TEXTOS } from '../textos';

/** Item do menu que ainda não foi feito (as telas vêm nas próximas tarefas). */
export function TelaEmBreve({ nome }: { nome: string }) {
  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{nome}</h1>
      <Aviso tipo="informacao" titulo={TEXTOS.emBreve.titulo}>
        {TEXTOS.emBreve.texto(nome)}
      </Aviso>
    </div>
  );
}
