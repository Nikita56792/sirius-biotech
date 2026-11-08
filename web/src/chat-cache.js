// Server messages are immutable and ordered by numeric id. Store the contiguous
// oldest-first prefix independently of request page size, plus confirmed tail messages.
export function messageRoute(path) {
  const url = new URL(path, 'https://cache.invalid');
  if (!/^\/chats\/[^/]+\/messages$/.test(url.pathname)) return null;
  return {key: '@conversation:' + url.pathname, path: url.pathname,
    offset: Number(url.searchParams.get('offset') || 0), limit: Number(url.searchParams.get('limit') || 50)};
}
export function allMessages(saved) {
  return [...new Map([...(saved?.items || []), ...(saved?.tail || [])].map(m => [String(m.id), m])).values()].sort((a,b) => Number(a.id)-Number(b.id));
}
export function mergeMessagePage(saved, rows, offset, limit) {
  const items = [...(saved?.items || [])];
  if (offset > items.length) return saved;
  items.splice(offset, rows.length, ...rows);
  if (rows.length < limit) items.length = offset + rows.length;
  const ids = new Set(items.map(m => String(m.id)));
  return {items, complete: rows.length < limit, tail: (saved?.tail || []).filter(m => !ids.has(String(m.id)))};
}
export function recordMessage(saved, message) {
  const result = {items: [...(saved?.items || [])], complete: saved?.complete || false, tail: [...(saved?.tail || [])]};
  if (!allMessages(result).some(m => String(m.id) === String(message.id))) {
    // Other messages may have arrived since the last read: retain the ack in a
    // separate tail until a server page confirms its position in the history.
    result.tail.push(message);
  }
  return result;
}
export function messagePage(saved, offset, limit) {
  if (!saved || offset > saved.items.length || offset === saved.items.length && !saved.complete) return undefined;
  return saved.items.slice(offset, offset + limit);
}
