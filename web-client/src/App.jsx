import { useEffect, useMemo, useRef, useState } from 'react'

function api(path, options = {}) {
  return fetch(path, {
    credentials: 'include',
    ...options,
  }).then(async (response) => {
    const text = await response.text()
    let data = null
    try {
      data = text ? JSON.parse(text) : null
    } catch (_) {
      data = null
    }
    if (!response.ok) {
      throw new Error((data && data.error) || text || `HTTP ${response.status}`)
    }
    return data
  })
}

function escapePath(path) {
  return encodeURIComponent(path || '')
}

function dirname(path) {
  const parts = (path || '').split('/').filter(Boolean)
  parts.pop()
  return parts.join('/')
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb.toFixed(1)} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  return `${(mb / 1024).toFixed(2)} GB`
}

async function uploadOne(file, currentPath, folderUpload) {
  const relativeFolder = folderUpload && file.webkitRelativePath
    ? dirname(file.webkitRelativePath)
    : ''
  const mergedPath = [currentPath, relativeFolder].filter(Boolean).join('/')
  const url = `/api/files/upload?path=${encodeURIComponent(mergedPath)}&name=${encodeURIComponent(file.name)}`
  const response = await fetch(url, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/octet-stream',
      'X-File-Name': file.name,
    },
    body: file,
  })
  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || `Upload failed: ${file.name}`)
  }
}

export default function App() {
  const [boot, setBoot] = useState(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const [path, setPath] = useState('')
  const [items, setItems] = useState([])
  const [log, setLog] = useState('')

  const pairPollRef = useRef(null)
  const heartbeatRef = useRef(null)

  const paired = !!boot?.paired
  const pairQrSrc = useMemo(() => {
    if (!boot?.pairQrPayload) return ''
    return `/api/qr?value=${encodeURIComponent(boot.pairQrPayload)}`
  }, [boot])

  function clearTimers() {
    if (pairPollRef.current) {
      clearInterval(pairPollRef.current)
      pairPollRef.current = null
    }
    if (heartbeatRef.current) {
      clearInterval(heartbeatRef.current)
      heartbeatRef.current = null
    }
  }

  async function loadPath(nextPath) {
    try {
      const data = await api(`/api/files/list?path=${escapePath(nextPath)}`)
      setPath(data.path || '')
      setItems(data.items || [])
      setLog('')
    } catch (err) {
      setError(err.message || 'Failed to load files')
    }
  }

  async function bootstrap() {
    clearTimers()
    setLoading(true)
    setError('')
    try {
      const data = await api('/api/bootstrap')
      setBoot(data)
      if (data.paired) {
        await loadPath('')
      }
    } catch (err) {
      setError(err.message || 'Failed to bootstrap')
    } finally {
      setLoading(false)
    }
  }

  async function quickReconnect() {
    setBusy(true)
    setError('')
    try {
      await api('/api/pair/quick', { method: 'POST' })
      await bootstrap()
    } catch (err) {
      setError(err.message || 'Quick reconnect failed')
    } finally {
      setBusy(false)
    }
  }

  async function disconnect() {
    setBusy(true)
    setError('')
    try {
      await api('/api/session/disconnect', { method: 'POST' })
      await bootstrap()
    } catch (err) {
      setError(err.message || 'Disconnect failed')
    } finally {
      setBusy(false)
    }
  }

  async function uploadFiles(fileList, folderUpload) {
    if (!fileList || fileList.length === 0) return
    setBusy(true)
    setError('')
    try {
      for (const file of fileList) {
        setLog(`Uploading ${file.webkitRelativePath || file.name}`)
        await uploadOne(file, path, folderUpload)
      }
      setLog('Upload complete')
      await loadPath(path)
    } catch (err) {
      setError(err.message || 'Upload failed')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    bootstrap()
    return () => clearTimers()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    clearTimers()
    if (!boot) return undefined

    if (!boot.paired && boot.pairToken) {
      pairPollRef.current = setInterval(async () => {
        try {
          const status = await api(`/api/pair/status?token=${encodeURIComponent(boot.pairToken)}`)
          if (status.status === 'approved' || status.status === 'not_found') {
            await bootstrap()
          }
        } catch (_) {
        }
      }, 2000)
      return () => clearTimers()
    }

    if (boot.paired) {
      heartbeatRef.current = setInterval(() => {
        api('/api/heartbeat', { method: 'POST' }).catch(() => {})
      }, 10000)
      return () => clearTimers()
    }

    return undefined
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [boot])

  if (loading) {
    return (
      <main className="shell">
        <section className="panel"><p className="status">Loading MediaBus...</p></section>
      </main>
    )
  }

  return (
    <main className="shell">
      <section className="panel">
        <header className="hero">
          <h1>MediaBus</h1>
          <p>Secure local file sharing</p>
        </header>

        {error && <div className="error">{error}</div>}

        {!paired && boot && (
          <section className="pairing">
            <div className="pair-info">
              <h2>Pair This Browser</h2>
              <p>Scan the QR in the host app or enter this pairing code on host.</p>
              <div className="pair-code">{boot.pairCode}</div>
              {boot.quickPairAvailable && (
                <button className="btn" disabled={busy} onClick={quickReconnect}>Quick Reconnect</button>
              )}
            </div>
            <div className="pair-qr-wrap">
              <img className="pair-qr" src={pairQrSrc} alt="Pairing QR" />
            </div>
          </section>
        )}

        {paired && (
          <section className="files">
            <div className="toolbar">
              <button className="btn" disabled={busy} onClick={() => loadPath(dirname(path))}>Up</button>
              <label className="btn file-btn">
                Upload Files
                <input
                  type="file"
                  multiple
                  onChange={(e) => uploadFiles(e.currentTarget.files, false)}
                />
              </label>
              <label className="btn file-btn">
                Upload Folder
                <input
                  type="file"
                  webkitdirectory=""
                  directory=""
                  multiple
                  onChange={(e) => uploadFiles(e.currentTarget.files, true)}
                />
              </label>
              <button className="btn btn-danger" disabled={busy} onClick={disconnect}>Disconnect</button>
            </div>

            <div className="path">Path: /{path}</div>
            <div className="status">{log}</div>

            <div className="table-wrap">
              <table>
                <tbody>
                  {items.map((item) => (
                    item.directory ? (
                      <tr key={item.path}>
                        <td>
                          <button className="link-btn" onClick={() => loadPath(item.path)}>
                            <span className="folder">📁</span> {item.name}
                          </button>
                        </td>
                        <td className="size">
                          <a className="btn slim" href={`/api/files/download-zip?path=${encodeURIComponent(item.path)}`}>Download ZIP</a>
                        </td>
                      </tr>
                    ) : (
                      <tr key={item.path}>
                        <td>{item.name}</td>
                        <td className="size">
                          {formatBytes(item.size)}
                          <a className="btn slim" href={`/api/files/download?path=${encodeURIComponent(item.path)}`}>Download</a>
                        </td>
                      </tr>
                    )
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )}
      </section>
    </main>
  )
}
