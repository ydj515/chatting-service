export function buildRequestPlans({ baseUrl, scenario, roomId, query, limit, from, to }) {
  const normalizedBaseUrl = String(baseUrl).replace(/\/+$/, '');
  const plans = [];

  if (scenario === 'history' || scenario === 'both') {
    const url = new URL(`${normalizedBaseUrl}/admin/chat-rooms/${roomId}/messages`);
    url.searchParams.set('limit', String(limit));
    if (from) url.searchParams.set('from', from);
    if (to) url.searchParams.set('to', to);
    plans.push({
      name: 'history',
      url: url.toString(),
    });
  }

  if (scenario === 'search' || scenario === 'both') {
    const url = new URL(`${normalizedBaseUrl}/admin/messages/search`);
    url.searchParams.set('q', query);
    url.searchParams.set('limit', String(limit));
    if (roomId) url.searchParams.set('roomId', String(roomId));
    if (from) url.searchParams.set('from', from);
    if (to) url.searchParams.set('to', to);
    plans.push({
      name: 'search',
      url: url.toString(),
    });
  }

  return plans;
}
