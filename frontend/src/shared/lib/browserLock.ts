export type BrowserLockResult<Value> =
  | { status: 'completed'; value: Value }
  | { status: 'busy' }
  | { status: 'unsupported' }
  | { status: 'failed'; error: unknown }

export async function runWithBrowserLock<Value>(
  name: string,
  operation: () => Value | PromiseLike<Value>,
): Promise<BrowserLockResult<Value>> {
  let lockManager: LockManager | undefined
  try {
    lockManager = navigator.locks as LockManager | undefined
  } catch (error) {
    return { status: 'failed', error }
  }
  if (!lockManager) return { status: 'unsupported' }

  try {
    return await lockManager.request(
      name,
      { ifAvailable: true },
      async (lock): Promise<BrowserLockResult<Value>> => {
        if (!lock) return { status: 'busy' }
        try {
          return { status: 'completed', value: await operation() }
        } catch (error) {
          return { status: 'failed', error }
        }
      },
    )
  } catch (error) {
    return { status: 'failed', error }
  }
}
