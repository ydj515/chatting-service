export const logoutSession = async (revoke: () => Promise<void>, clear: () => void): Promise<void> => {
  await revoke();
  clear();
};
