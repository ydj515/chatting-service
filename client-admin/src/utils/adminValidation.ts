export const numberOrNull = (value: string): number | null => {
  if (value.trim() === '') return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
};

export const isValidRoomId = (value: string): boolean => {
  return value.trim() === '' || numberOrNull(value) !== null;
};
