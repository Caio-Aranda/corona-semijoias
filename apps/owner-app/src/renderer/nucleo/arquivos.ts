/** Ler um arquivo escolhido pela pessoa e entregar um arquivo recebido da API. */

/** Lê o arquivo inteiro como bytes (nunca como texto). */
export async function lerBytes(arquivo: File): Promise<Uint8Array> {
  if (typeof arquivo.arrayBuffer === 'function') {
    return new Uint8Array(await arquivo.arrayBuffer());
  }
  return new Promise((resolve, reject) => {
    const leitor = new FileReader();
    leitor.onload = () => resolve(new Uint8Array(leitor.result as ArrayBuffer));
    leitor.onerror = () => reject(leitor.error ?? new Error('leitura'));
    leitor.readAsArrayBuffer(arquivo);
  });
}

/** Entrega o arquivo ao computador da pessoa (o Windows pergunta onde guardar). */
export function entregarArquivo(nome: string, tipo: string, bytes: Uint8Array): void {
  const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type: tipo }));
  const ancora = document.createElement('a');
  ancora.href = url;
  ancora.download = nome;
  document.body.appendChild(ancora);
  ancora.click();
  ancora.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/** "1,2 MB", "340 KB". */
export function tamanhoNaTela(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} bytes`;
}
