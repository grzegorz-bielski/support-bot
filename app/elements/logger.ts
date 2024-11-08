export const logger = !!localStorage.getItem("loggingEnabled")
  ? {
      info(...data: any[]) {
        console.info(...data);
      },
    }
  : { info(...data: any[]) {} };
