export function parseRooms(value) {
  const rooms = String(value)
    .split(',')
    .filter(Boolean)
    .flatMap((entry) => {
      const [rawHeat, rawCount] = entry.split(':');
      const heat = heatLevel(rawHeat);
      const count = positiveInteger(rawCount, `room count for ${rawHeat}`);
      return Array.from({ length: count }, (_, index) => ({
        id: roomIdFor(heat, index),
        heatLevel: heat,
        weight: heatWeight(heat),
      }));
    });

  if (rooms.length === 0) {
    throw new Error('--rooms must contain at least one room.');
  }
  return rooms;
}

function positiveInteger(value, name) {
  const parsed = Number.parseInt(String(value), 10);
  if (!Number.isSafeInteger(parsed) || parsed < 1 || String(value).match(/[^0-9]/)) {
    throw new Error(`${name} must be a positive integer.`);
  }
  return parsed;
}

function heatLevel(value) {
  const normalized = String(value ?? '').trim().toLowerCase();
  if (normalized === 'normal') return 'NORMAL';
  if (normalized === 'hot') return 'HOT';
  if (normalized === 'very-hot' || normalized === 'very_hot') return 'VERY_HOT';
  throw new Error(`Unsupported room heat level: ${value}`);
}

function heatWeight(heat) {
  if (heat === 'VERY_HOT') return 20;
  if (heat === 'HOT') return 5;
  return 1;
}

function roomIdFor(heat, index) {
  const base = heat === 'VERY_HOT' ? 30_000 : heat === 'HOT' ? 20_000 : 10_000;
  return base + index + 1;
}
