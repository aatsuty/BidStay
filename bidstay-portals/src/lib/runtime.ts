export const demoMode = import.meta.env.DEV
  || document.querySelector<HTMLMetaElement>('meta[name="bidstay-demo"]')?.content === 'enabled';
