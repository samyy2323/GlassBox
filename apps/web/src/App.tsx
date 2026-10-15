import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Shell } from './components/Shell'
import { Splash } from './components/Splash'
import { AuthProvider, useAuth } from './lib/auth'
import { UploadsProvider } from './lib/uploads'
import { WorkspaceProvider } from './lib/workspace'
import Calibration from './pages/Calibration'
import Chat from './pages/Chat'
import DocumentChanges from './pages/DocumentChanges'
import Documents from './pages/Documents'
import ExperimentDetail from './pages/ExperimentDetail'
import ExperimentNew from './pages/ExperimentNew'
import Experiments from './pages/Experiments'
import AutotuneDetail from './pages/AutotuneDetail'
import Budget from './pages/Budget'
import AutotuneNew from './pages/AutotuneNew'
import Autotunes from './pages/Autotunes'
import Feedback from './pages/Feedback'
import Login from './pages/Login'
import TestSetReview from './pages/TestSetReview'
import TestSets from './pages/TestSets'
import XRay from './pages/XRay'
import XRayList from './pages/XRayList'

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } } })

function Protected() {
  const { user, loading } = useAuth()
  if (loading) {
    return (
      <div className="grid h-dvh place-items-center bg-canvas" aria-busy="true" />
    )
  }
  if (!user) return <Navigate to="/login" replace />
  return (
    <WorkspaceProvider>
      <UploadsProvider>
        <Shell />
      </UploadsProvider>
    </WorkspaceProvider>
  )
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <Splash />
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route element={<Protected />}>
              <Route path="/chat" element={<Chat />} />
              <Route path="/chat/:traceId" element={<Chat />} />
              <Route path="/documents" element={<Documents />} />
              <Route path="/testsets" element={<TestSets />} />
              <Route path="/testsets/:id/review" element={<TestSetReview />} />
              <Route path="/experiments" element={<Experiments />} />
              <Route path="/budget" element={<Budget />} />
              <Route path="/documents/:id/changes" element={<DocumentChanges />} />
              <Route path="/feedback" element={<Feedback />} />
              <Route path="/autotune" element={<Autotunes />} />
              <Route path="/autotune/new" element={<AutotuneNew />} />
              <Route path="/autotune/:id" element={<AutotuneDetail />} />
              <Route path="/experiments/new" element={<ExperimentNew />} />
              <Route path="/experiments/:id" element={<ExperimentDetail />} />
              <Route path="/calibration" element={<Calibration />} />
              <Route path="/xray" element={<XRayList />} />
              <Route path="/xray/:traceId" element={<XRay />} />
              <Route path="*" element={<Navigate to="/chat" replace />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  )
}
