export const buildWebSocketTicketUrl = (baseUrl: string, ticket: string): string => {
  const url = new URL(baseUrl);
  url.searchParams.set('ticket', ticket);
  url.searchParams.delete('token');
  return url.toString();
};
