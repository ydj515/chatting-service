import React from 'react';
import type { AdminExportJob } from '@/types/index.ts';
import Button from '@/components/ui/Button.tsx';

interface ExportStatusProps {
  job: AdminExportJob | null;
  busy: boolean;
  onRefresh: () => void;
}

const ExportStatus: React.FC<ExportStatusProps> = ({ job, busy, onRefresh }) => {
  if (!job) {
    return null;
  }

  return (
    <div className="flex items-center justify-end gap-2.5 flex-wrap min-h-10 pt-3 border-t border-border-light text-text-secondary text-[13px]">
      <span>
        Export <b className="text-text-primary font-bold">{job.jobId}</b> · {job.status}
      </span>
      {typeof job.exportedRows === 'number' && (
        <span>{job.exportedRows.toLocaleString()} rows</span>
      )}
      {job.downloadUrl && (
        <a
          href={job.downloadUrl}
          target="_blank"
          rel="noreferrer"
          className="text-primary font-bold no-underline hover:text-primary-hover hover:underline"
        >
          Download
        </a>
      )}
      <Button variant="ghost" size="sm" onClick={onRefresh} disabled={busy}>
        Refresh Export
      </Button>
    </div>
  );
};

export default ExportStatus;
